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
 * consumer must dedupe), and every aggregate's audit rows are in outbox order; the same relay
 * instance holds the leader lock before, during and after the outage (leadership lives in Postgres,
 * so a Kafka outage must not move it); InvariantChecker clean; reconciliation of the day at 0
 * mismatches. Recorded: backlog peak, drain time after Kafka returns, the leader before and after.
 */
@DisplayName("C5: Kafka outage")
class C5KafkaOutageChaosIT extends AbstractChaosIT {

    @org.springframework.beans.factory.annotation.Autowired
    private com.svatsa.escrow.outbox.OutboxRelay relay;

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
        String pendingSql = "SELECT count(*) FROM outbox WHERE id > ? AND published_at IS NULL";

        // --- The outage, on its own thread ---------------------------------------------------------
        AtomicBoolean paused = new AtomicBoolean(false);
        AtomicLong maxPendingDuringOutage = new AtomicLong();
        AtomicLong drainMs = new AtomicLong(-1);
        Map<String, Object> leaderBefore = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Set<String> leadersDuringOutage = new java.util.concurrent.ConcurrentSkipListSet<>();
        Thread outage = Thread.ofPlatform().name("c5-outage").start(() -> {
            try {
                Thread.sleep(OUTAGE_START_MS);
                leaderBefore.putAll(leader());
                pauseKafka();
                paused.set(true);
                log.warn("c5.kafka-paused for={}ms leader={}", OUTAGE_MS, leaderBefore);
                long end = System.currentTimeMillis() + OUTAGE_MS;
                while (System.currentTimeMillis() < end) {
                    long pending = jdbcClient.sql(pendingSql).param(firstOutboxId).query(Long.class).single();
                    maxPendingDuringOutage.accumulateAndGet(pending, Math::max);
                    leadersDuringOutage.add(String.valueOf(leader()));
                    Thread.sleep(1_000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (paused.get()) {
                    unpauseKafka();
                    long unpausedAt = System.nanoTime();
                    log.warn("c5.kafka-unpaused");
                    // Drain time: Kafka back -> the scenario's backlog first reaches zero. Operations
                    // keep arriving meanwhile, so this is "caught up", not "nothing left forever".
                    long deadline = unpausedAt + Duration.ofMinutes(3).toNanos();
                    while (System.nanoTime() < deadline) {
                        if (jdbcClient.sql(pendingSql).param(firstOutboxId).query(Long.class).single() == 0) {
                            drainMs.set((System.nanoTime() - unpausedAt) / 1_000_000);
                            break;
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
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
        // Per-aggregate order: each aggregate's audit rows (first delivery, deduped) in outbox order.
        List<String> outOfOrder = jdbcClient.sql("""
                WITH o AS (
                    SELECT event_id, aggregate_type, aggregate_id,
                           row_number() OVER (PARTITION BY aggregate_type, aggregate_id ORDER BY id) AS rn
                    FROM outbox WHERE id > ?),
                a AS (
                    SELECT a.event_id,
                           row_number() OVER (PARTITION BY o.aggregate_type, o.aggregate_id ORDER BY a.id) AS rn
                    FROM audit_log a JOIN o ON o.event_id = a.event_id)
                SELECT DISTINCT o.aggregate_type || ':' || o.aggregate_id
                FROM o JOIN a ON a.event_id = o.event_id
                WHERE o.rn <> a.rn
                """).param(firstOutboxId).query(String.class).list();
        if (!outOfOrder.isEmpty()) {
            failures.add("audit_log order differs from outbox order for aggregates: " + outOfOrder);
        }
        long aggregates = jdbcClient.sql("SELECT count(DISTINCT (aggregate_type, aggregate_id)) FROM outbox WHERE id > ?")
            .param(firstOutboxId).query(Long.class).single();
        // Leadership lives in Postgres: a Kafka outage must not move it.
        Map<String, Object> leaderAfter = leader();
        if (leaderBefore.isEmpty() || !Boolean.TRUE.equals(leaderBefore.get("thisRelayLeads"))) {
            failures.add("this JVM's relay did not hold the leader lock before the outage: " + leaderBefore);
        }
        if (!new java.util.TreeMap<>(leaderBefore).equals(new java.util.TreeMap<>(leaderAfter))) {
            failures.add("leadership moved across a Kafka outage: before=" + leaderBefore + " after=" + leaderAfter);
        }
        if (leadersDuringOutage.size() != 1) {
            failures.add("leadership changed during the outage: " + leadersDuringOutage);
        }
        if (drainMs.get() < 0) {
            failures.add("backlog never reached zero within 3 minutes of Kafka returning");
        }
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
        notes.put("drainMsAfterKafkaReturned", drainMs.get());
        notes.put("leaderBeforeOutage", new java.util.TreeMap<>(leaderBefore));
        notes.put("leadersSeenDuringOutage", leadersDuringOutage.size());
        notes.put("leaderAfterDrain", new java.util.TreeMap<>(leaderAfter));
        notes.put("aggregates", aggregates);
        notes.put("aggregatesOutOfOrder", outOfOrder.size());
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

    /** Who holds the relay's advisory lock: this JVM's relay instance, and the backend pid holding it. */
    private Map<String, Object> leader() {
        long key = com.svatsa.escrow.outbox.OutboxRelayLeadership.LOCK_KEY;
        long pid = jdbcClient.sql("""
                SELECT COALESCE(max(pid), 0) FROM pg_locks
                WHERE locktype = 'advisory' AND granted AND objsubid = 1 AND classid::bigint = ? AND objid::bigint = ?
                """).param(key >>> 32).param(key & 0xFFFFFFFFL).query(Long.class).single();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instance", relay.instanceName());
        m.put("thisRelayLeads", relay.isLeader());
        m.put("lockHolderPid", pid);
        return m;
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
