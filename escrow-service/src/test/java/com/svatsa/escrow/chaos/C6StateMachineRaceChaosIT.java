package com.svatsa.escrow.chaos;

import com.svatsa.escrow.escrow.AutoReleaseJob;
import com.svatsa.escrow.escrow.EscrowService;
import com.svatsa.escrow.escrow.MilestoneRepository;
import com.svatsa.escrow.escrow.Project;
import com.svatsa.escrow.ledger.LedgerKeys;
import com.svatsa.escrow.test.Actors;
import com.svatsa.escrow.test.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.svatsa.escrow.test.ApiClient.actor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * C6: approve vs auto-release vs dispute at the review-window boundary.
 *
 * 100 submitted milestones (10 projects x 10). The clock is set to the exact moment the last one's
 * review window ends, so every milestone is eligible for auto-release, and then, released by one
 * latch: for every milestone the client's approve and the freelancer's dispute go out over HTTP
 * concurrently while 4 threads run AutoReleaseJob in a loop.
 *
 * Pass: every milestone ends in exactly one of RELEASED or DISPUTED, with exactly one money
 * movement: one RELEASE entry and no DISPUTE_FREEZE, or the other way round. Every HTTP answer is a
 * success or 409 INVALID_STATE_TRANSITION (never a 5xx). InvariantChecker clean (escrow
 * conservation included); reconciliation of the day at 0 mismatches.
 */
@DisplayName("C6: approve vs auto-release vs dispute storm")
class C6StateMachineRaceChaosIT extends AbstractChaosIT {

    private static final Logger log = LoggerFactory.getLogger(C6StateMachineRaceChaosIT.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final int PROJECTS = 10;
    private static final int MILESTONES_PER_PROJECT = 10;
    private static final Duration REVIEW_WINDOW = Duration.ofHours(1);

    @Autowired private EscrowService escrowService;
    @Autowired private MilestoneRepository milestoneRepository;
    @Autowired private AutoReleaseJob autoReleaseJob;

    @Test
    @DisplayName("each of 100 milestones ends RELEASED xor DISPUTED with exactly one money movement")
    void raceAtTheBoundary() throws Exception {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        // Submission times are DB now() (wall clock), so this scenario's day is the real one.
        clock.reset();
        ApiClient api = new ApiClient(serverPort);
        String run = UUID.randomUUID().toString().substring(0, 8);
        long client = api.createUser("c6-client-" + run, "CLIENT");
        long freelancer = api.createUser("c6-freelancer-" + run, "FREELANCER");
        long wallet = jdbcClient.sql("SELECT id FROM accounts WHERE code = ?").param("CLIENT_WALLET:" + client)
            .query(Long.class).single();
        api.expect(api.post("/api/v1/admin/deposits/simulate", "c6-dep-" + run, ApiClient.admin(),
            "{\"clientAccountId\":" + wallet + ",\"amountPaise\":100000000}"), 201);

        List<Long> milestones = new ArrayList<>();
        for (int p = 0; p < PROJECTS; p++) {
            List<EscrowService.MilestoneSpec> specs = new ArrayList<>();
            for (int m = 0; m < MILESTONES_PER_PROJECT; m++) {
                specs.add(new EscrowService.MilestoneSpec("m" + m, 10_000 + 100L * m, Instant.now().plus(Duration.ofDays(30))));
            }
            Project project = escrowService.createProject(Actors.client(client), client, freelancer, "c6-" + p, REVIEW_WINDOW, specs);
            escrowService.fundProject(Actors.client(client), project.id(), "c6-fund-" + run + p);
            for (var m : milestoneRepository.findByProjectId(project.id())) {
                escrowService.submitMilestone(Actors.freelancer(freelancer), m.id(), "c6-sub-" + m.id());
                milestones.add(m.id());
            }
        }

        // The boundary: the last submission's review window ends exactly now.
        Instant lastSubmitted = jdbcClient.sql("SELECT max(submitted_at) FROM milestones WHERE id = ANY(?)")
            .param(milestones.toArray(new Long[0])).query(java.time.OffsetDateTime.class).single().toInstant();
        awaitOutboxDrained(); // the jump is longer than I9's 5 minutes
        clock.set(lastSubmitted.plus(REVIEW_WINDOW));
        scenarioDay = LocalDate.ofInstant(clock.instant(), IST);

        // --- The storm ------------------------------------------------------------------------------
        Map<Integer, Integer> statuses = new ConcurrentHashMap<>();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go = new CountDownLatch(1);
        AtomicBoolean stormOver = new AtomicBoolean(false);
        try (ExecutorService pool = Executors.newFixedThreadPool(48)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int j = 0; j < 4; j++) {
                jobs.add(pool.submit(() -> {
                    go.await();
                    while (!stormOver.get()) {
                        autoReleaseJob.runOnce();
                    }
                    return null;
                }));
            }
            List<Future<?>> calls = new ArrayList<>();
            for (long m : milestones) {
                calls.add(pool.submit(() -> call(api, go, statuses, errors, "/api/v1/milestones/" + m + "/approve",
                    "c6-appr-" + m, actor(client, "CLIENT"), "")));
                calls.add(pool.submit(() -> call(api, go, statuses, errors, "/api/v1/disputes",
                    "c6-disp-" + m, actor(freelancer, "FREELANCER"), "{\"milestoneId\":" + m + ",\"reason\":\"c6\"}")));
            }
            go.countDown();
            for (Future<?> f : calls) {
                f.get(2, TimeUnit.MINUTES);
            }
            stormOver.set(true);
            for (Future<?> f : jobs) {
                f.get(1, TimeUnit.MINUTES);
            }
        }
        autoReleaseJob.runOnce();   // anything the storm left SUBMITTED is past its window: release it

        // --- Verify ---------------------------------------------------------------------------------
        List<String> failures = new ArrayList<>(errors);
        if (!java.util.Set.of(200, 201, 409).containsAll(statuses.keySet())) {
            failures.add("unexpected HTTP statuses: " + statuses);
        }
        int released = 0, disputed = 0, autoReleased = 0;
        for (long m : milestones) {
            String state = milestoneRepository.findById(m).orElseThrow().state().name();
            long releases = entries(LedgerKeys.release(m));
            long freezes = entries(LedgerKeys.disputeFreeze(m));
            switch (state) {
                case "RELEASED" -> {
                    released++;
                    if (releases != 1 || freezes != 0) {
                        failures.add(m + " RELEASED with " + releases + " release / " + freezes + " freeze entries");
                    }
                    autoReleased += jdbcClient.sql("SELECT count(*) FROM milestone_transitions WHERE milestone_id = ? "
                        + "AND to_state = 'RELEASED' AND actor_role = 'SYSTEM'").param(m).query(Integer.class).single();
                }
                case "DISPUTED" -> {
                    disputed++;
                    if (freezes != 1 || releases != 0) {
                        failures.add(m + " DISPUTED with " + releases + " release / " + freezes + " freeze entries");
                    }
                }
                default -> failures.add(m + " ended " + state + ", expected RELEASED or DISPUTED");
            }
        }

        List<String> violations = new ArrayList<>(failures);
        violations.addAll(describe(invariantChecker.check()));
        List<String> mismatches = reconcileToday();
        int operations = milestones.size() * 2;

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("milestones", milestones.size());
        notes.put("released", released);
        notes.put("releasedByAutoRelease", autoReleased);
        notes.put("disputed", disputed);
        notes.put("httpStatuses", statuses);
        notes.put("boundary", clock.instant().toString());
        var file = ChaosResults.write("C6-state-machine-race", startedAt,
            Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), operations, violations, mismatches, notes);
        log.info("c6.result file={} violations={} mismatches={} notes={}", file, violations.size(), mismatches.size(), notes);

        assertThat(violations).as("C6 violations").isEmpty();
        assertThat(mismatches).as("C6 reconciliation mismatches").isEmpty();
        assertThat(released + disputed).isEqualTo(milestones.size());
    }

    private static Void call(ApiClient api, CountDownLatch go, Map<Integer, Integer> statuses, List<String> errors,
                             String path, String key, String[] actor, String body) throws Exception {
        go.await();
        try {
            HttpResponse<String> r = api.post(path, key, actor, body);
            statuses.merge(r.statusCode(), 1, Integer::sum);
            if (r.statusCode() == 409 && !r.body().contains("INVALID_STATE_TRANSITION")) {
                errors.add(path + " 409 without INVALID_STATE_TRANSITION: " + r.body());
            }
        } catch (Exception e) {
            errors.add(path + ": " + e);
        }
        return null;
    }

    private long entries(String key) {
        return jdbcClient.sql("SELECT count(*) FROM journal_entries WHERE idempotency_key = ?").param(key)
            .query(Long.class).single();
    }
}
