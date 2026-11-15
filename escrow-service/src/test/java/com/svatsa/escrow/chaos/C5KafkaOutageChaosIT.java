package com.svatsa.escrow.chaos;

import com.svatsa.escrow.test.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.svatsa.escrow.test.ApiClient.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * C5: Kafka outage.
 *
 * 200 escrow operations through the HTTP API (50 projects: create, fund, submit, approve), one
 * every 250 ms. Ten seconds in, the Kafka broker container is frozen for 30 s with {@code docker
 * pause}, the Testcontainers equivalent of the broker vanishing: connections hang and every send
 * times out. (A {@code docker stop}/{@code start} would rebind Kafka's random host port and break
 * the app's bootstrap address for every later test in the JVM.)
 *
 * Pass: the API never fails (every operation answered its success status); the outbox backs up
 * during the outage and fully drains after it; audit_log has exactly ONE row per outbox eventId of
 * the scenario, none missing (the relay re-sends what timed out, so Kafka sees duplicates and the
 * consumer must dedupe); InvariantChecker clean; reconciliation of the day at 0 mismatches.
 */
@DisplayName("C5: Kafka outage")
class C5KafkaOutageChaosIT extends AbstractChaosIT {

    private static final Logger log = LoggerFactory.getLogger(C5KafkaOutageChaosIT.class);

    private static final int PROJECTS = 50;
    private static final long PACE_MS = 250;
    private static final long OUTAGE_START_MS = 10_000;
    private static final long OUTAGE_MS = 30_000;

    @Test
    @DisplayName("200 operations through a 30 s Kafka outage: no API failure, outbox drains, one audit row per event")
    void apiSurvivesKafkaOutage() throws Exception {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        ApiClient api = new ApiClient(serverPort);
        String run = UUID.randomUUID().toString().substring(0, 8);
        long client = api.createUser("c5-client-" + run, "CLIENT");
        long freelancer = api.createUser("c5-freelancer-" + run, "FREELANCER");
        long wallet = jdbcClient.sql("SELECT id FROM accounts WHERE code = ?").param("CLIENT_WALLET:" + client)
            .query(Long.class).single();
        api.expect(api.post("/api/v1/admin/deposits/simulate", "c5-dep-" + run, ApiClient.admin(),
            "{\"clientAccountId\":" + wallet + ",\"amountPaise\":10000000}"), 201);
        long firstOutboxId = jdbcClient.sql("SELECT COALESCE(max(id), 0) FROM outbox").query(Long.class).single();

        // --- The outage, on its own thread ---------------------------------------------------------
        AtomicBoolean paused = new AtomicBoolean(false);
        AtomicLong maxPendingDuringOutage = new AtomicLong();
        Thread outage = Thread.ofPlatform().name("c5-outage").start(() -> {
            try {
                Thread.sleep(OUTAGE_START_MS);
                pauseKafka();
                paused.set(true);
                log.warn("c5.kafka-paused for={}ms", OUTAGE_MS);
                long end = System.currentTimeMillis() + OUTAGE_MS;
                while (System.currentTimeMillis() < end) {
                    long pending = jdbcClient.sql("SELECT count(*) FROM outbox WHERE id > ? AND published_at IS NULL")
                        .param(firstOutboxId).query(Long.class).single();
                    maxPendingDuringOutage.accumulateAndGet(pending, Math::max);
                    Thread.sleep(1_000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (paused.get()) {
                    unpauseKafka();
                    log.warn("c5.kafka-unpaused");
                }
            }
        });

        // --- 200 operations -----------------------------------------------------------------------
        List<String> failures = new ArrayList<>();
        int ok = 0;
        int duringOutage = 0;
        String due = Instant.now().plus(Duration.ofDays(30)).toString();
        for (int p = 0; p < PROJECTS; p++) {
            HttpResponse<String> created = op(api.post("/api/v1/projects", null, actor(client, "CLIENT"),
                "{\"clientId\":" + client + ",\"freelancerId\":" + freelancer + ",\"title\":\"c5-" + p + "\","
                    + "\"reviewWindow\":\"P5D\",\"milestones\":[{\"title\":\"m\",\"amountPaise\":10000,\"dueAt\":\"" + due + "\"}]}"));
            if (created.statusCode() != 201) {
                failures.add("create project -> " + created.statusCode() + " " + created.body());
                continue;
            }
            ok++;
            long projectId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.body()).path("id").asLong();
            duringOutage += paused.get() ? 1 : 0;
            ok += expect(failures, op(api.post("/api/v1/projects/" + projectId + "/fund", "c5-fund-" + run + p,
                actor(client, "CLIENT"), "")), 200);
            long milestone = jdbcClient.sql("SELECT id FROM milestones WHERE project_id = ?").param(projectId)
                .query(Long.class).single();
            duringOutage += paused.get() ? 1 : 0;
            ok += expect(failures, op(api.post("/api/v1/milestones/" + milestone + "/submit", "c5-sub-" + run + p,
                actor(freelancer, "FREELANCER"), "")), 200);
            duringOutage += paused.get() ? 1 : 0;
            ok += expect(failures, op(api.post("/api/v1/milestones/" + milestone + "/approve", "c5-appr-" + run + p,
                actor(client, "CLIENT"), "")), 200);
            duringOutage += paused.get() ? 1 : 0;
        }
        outage.join();
        int operations = PROJECTS * 4;

        // --- After the outage: drain, then exactly one audit row per event --------------------------
        boolean drained = awaitQuietly(Duration.ofMinutes(3), () -> jdbcClient.sql(
            "SELECT count(*) FROM outbox WHERE id > ? AND published_at IS NULL").param(firstOutboxId).query(Long.class).single() == 0);
        if (!drained) {
            failures.add("outbox did not drain within 3 minutes of the outage ending");
        }
        String missingSql = """
            SELECT count(*) FROM outbox o
            WHERE o.id > ? AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.event_id = o.event_id)
            """;
        boolean audited = awaitQuietly(Duration.ofMinutes(2), () ->
            jdbcClient.sql(missingSql).param(firstOutboxId).query(Long.class).single() == 0);
        long missing = jdbcClient.sql(missingSql).param(firstOutboxId).query(Long.class).single();
        if (!audited) {
            failures.add(missing + " outbox events never reached audit_log");
        }
        List<String> duplicated = jdbcClient.sql("""
                SELECT a.event_id || ' x' || count(*) FROM audit_log a
                JOIN outbox o ON o.event_id = a.event_id AND o.id > ?
                GROUP BY a.event_id HAVING count(*) > 1
                """).param(firstOutboxId).query(String.class).list();
        if (!duplicated.isEmpty()) {
            failures.add("audit_log has duplicate rows for events: " + duplicated);
        }
        long events = jdbcClient.sql("SELECT count(*) FROM outbox WHERE id > ?").param(firstOutboxId).query(Long.class).single();
        if (maxPendingDuringOutage.get() == 0) {
            failures.add("nothing backed up in the outbox during the outage: the fault didn't bite");
        }
        if (duringOutage == 0) {
            failures.add("no operation ran during the outage");
        }

        List<String> violations = new ArrayList<>(failures);
        violations.addAll(describe(invariantChecker.check()));
        List<String> mismatches = reconcileToday();

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("operations", operations);
        notes.put("succeeded", ok);
        notes.put("operationsDuringOutage", duringOutage);
        notes.put("kafkaPausedMs", OUTAGE_MS);
        notes.put("maxOutboxPendingDuringOutage", maxPendingDuringOutage.get());
        notes.put("outboxEvents", events);
        notes.put("auditMissing", missing);
        notes.put("auditDuplicates", duplicated.size());
        var file = ChaosResults.write("C5-kafka-outage", startedAt,
            Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), operations, violations, mismatches, notes);
        log.info("c5.result file={} violations={} mismatches={} notes={}", file, violations.size(), mismatches.size(), notes);

        assertThat(violations).as("C5 violations").isEmpty();
        assertThat(mismatches).as("C5 reconciliation mismatches").isEmpty();
        assertThat(ok).as("the API never failed").isEqualTo(operations);
    }

    private static HttpResponse<String> op(HttpResponse<String> r) throws InterruptedException {
        Thread.sleep(PACE_MS);
        return r;
    }

    private static int expect(List<String> failures, HttpResponse<String> r, int status) {
        if (r.statusCode() == status) {
            return 1;
        }
        failures.add(r.request().uri().getPath() + " -> " + r.statusCode() + " " + r.body());
        return 0;
    }

    private static boolean awaitQuietly(Duration max, java.util.concurrent.Callable<Boolean> condition) {
        try {
            await().atMost(max).pollInterval(Duration.ofSeconds(1)).until(condition);
            return true;
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            return false;
        }
    }
}
