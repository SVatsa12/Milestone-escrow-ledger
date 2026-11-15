package com.svatsa.escrow.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.AccountType;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3: database latency and connection drops.
 *
 * The Postgres proxy gets 300ms (±100ms jitter) latency for the whole run, and a monkey
 * thread adds a timeout toxic for 2s at random intervals, which stalls and then closes every
 * connection that touches the DB in that window, including connections mid-COMMIT. Meanwhile
 * 20 client threads POST /api/v1/transfers. On any failure (5xx, IO error, client timeout)
 * a client retries up to 5 attempts with the SAME Idempotency-Key, like a real client would.
 *
 * The dangerous case is the ambiguous commit: the server commits, the client never hears
 * back, and retries. Idempotency must turn that retry into a replay of the stored response.
 *
 * Pass criteria: each logical transfer is applied at most once; every one that got a 2xx is
 * applied exactly once, with the entry id the client was told; balances add up;
 * InvariantChecker clean.
 */
@DisplayName("C3: DB latency and drops")
class C3DBLatencyChaosIT extends AbstractChaosIT {

    private static final Logger log = LoggerFactory.getLogger(C3DBLatencyChaosIT.class);

    private static final int THREADS = 20;
    private static final int TRANSFERS_PER_THREAD = 5;
    private static final int MAX_ATTEMPTS = 5;
    private static final int WALLETS = 20;
    private static final long FUNDED_PAISE = 1_000_000L;
    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(15);

    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    private final ObjectMapper json = new ObjectMapper();

    /** What one logical transfer asked for and what the client saw. */
    record Logical(String key, long from, long to, long amount) {}

    record Outcome(int attempts, Integer finalStatus, Long ackedEntryId, String lastError) {
        boolean acked() {
            return finalStatus != null && finalStatus >= 200 && finalStatus < 300;
        }
    }

    @Test
    @DisplayName("retries with the same Idempotency-Key never apply a transfer twice")
    void dbFaultsNeverDoubleApplyATransfer() throws Exception {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        String run = UUID.randomUUID().toString().substring(0, 8);

        // --- Setup, before any fault ----------------------------------------------------------
        List<Long> wallets = new ArrayList<>();
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        for (int i = 0; i < WALLETS; i++) {
            long ownerId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
            long id = accountRepository.create("CLIENT_WALLET:" + ownerId, AccountType.CLIENT_WALLET, ownerId, false).id();
            tx.executeWithoutResult(s -> ledgerService.post(new PostingRequest(
                "TEST_SEED", "TEST_SEED", String.valueOf(id), "c3-seed:" + run + ":" + id, "C3 seed",
                List.of(new Leg(bank, -FUNDED_PAISE), new Leg(id, FUNDED_PAISE)))));
            wallets.add(id);
        }

        Random plan = new Random(3003);
        List<Logical> logicals = new ArrayList<>();
        for (int i = 0; i < THREADS * TRANSFERS_PER_THREAD; i++) {
            long from = wallets.get(plan.nextInt(WALLETS));
            long to;
            do {
                to = wallets.get(plan.nextInt(WALLETS));
            } while (to == from);
            logicals.add(new Logical("c3-" + run + "-" + i, from, to, 100 + plan.nextInt(900)));
        }

        // --- Faults on ------------------------------------------------------------------------
        PG_PROXY.toxics().latency("pg-latency", ToxicDirection.DOWNSTREAM, 300).setJitter(100);
        AtomicBoolean monkeyRunning = new AtomicBoolean(true);
        AtomicInteger dropWindows = new AtomicInteger();
        Thread monkey = Thread.ofPlatform().name("c3-monkey").start(() -> dropConnections(monkeyRunning, dropWindows));

        HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        Map<String, Outcome> outcomes = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                List<Logical> mine = logicals.subList(t * TRANSFERS_PER_THREAD, (t + 1) * TRANSFERS_PER_THREAD);
                futures.add(pool.submit(() -> {
                    for (Logical l : mine) {
                        outcomes.put(l.key(), transferWithRetries(http, l));
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.MINUTES);
            }
        } finally {
            monkeyRunning.set(false);
            monkey.join();
            removeAllToxics(PG_PROXY);
        }

        // --- Verify (network healed) ----------------------------------------------------------
        List<String> failures = new ArrayList<>();
        Map<Long, Long> expectedBalance = new HashMap<>();
        wallets.forEach(w -> expectedBalance.put(w, FUNDED_PAISE));
        int acked = 0, appliedWithoutAck = 0, notApplied = 0, totalAttempts = 0;

        for (Logical l : logicals) {
            Outcome o = outcomes.get(l.key());
            totalAttempts += o.attempts();
            List<Long> entries = jdbcClient.sql("SELECT id FROM journal_entries WHERE idempotency_key = ?")
                .param("transfer:" + l.key())
                .query(Long.class)
                .list();

            if (entries.size() > 1) {
                failures.add(l.key() + " applied " + entries.size() + " times");
                continue;
            }
            if (o.finalStatus() != null && o.finalStatus() >= 400 && o.finalStatus() < 500) {
                failures.add(l.key() + " got non-retryable " + o.finalStatus() + ": " + o.lastError());
            }
            if (o.acked()) {
                acked++;
                if (entries.isEmpty()) {
                    failures.add(l.key() + " was acknowledged (" + o.finalStatus() + ") but never applied");
                    continue;
                }
                if (!entries.get(0).equals(o.ackedEntryId())) {
                    failures.add(l.key() + " client was told entry " + o.ackedEntryId()
                        + " but the ledger has entry " + entries.get(0));
                }
            } else if (entries.isEmpty()) {
                notApplied++;
            } else {
                appliedWithoutAck++; // ambiguous commit the client gave up on: allowed, but must be exactly once
            }
            if (entries.size() == 1) {
                checkPostings(l, entries.get(0), failures);
                expectedBalance.merge(l.from(), -l.amount(), Long::sum);
                expectedBalance.merge(l.to(), l.amount(), Long::sum);
            }
        }

        long total = 0;
        for (long w : wallets) {
            long actual = accountRepository.findById(w).orElseThrow().balance();
            total += actual;
            if (actual != expectedBalance.get(w)) {
                failures.add("wallet " + w + " balance " + actual + " expected " + expectedBalance.get(w));
            }
        }
        if (total != FUNDED_PAISE * WALLETS) {
            failures.add("wallets hold " + total + " in total, expected " + FUNDED_PAISE * WALLETS);
        }

        // The fault must actually have bitten: at least 20% of all HTTP calls were retries. Otherwise
        // the toxics missed and "no transfer applied twice" proves nothing (FINDINGS #54).
        int retries = totalAttempts - logicals.size();
        if (retries * 5 < totalAttempts) {
            failures.add("fault did not bite: only " + retries + " of " + totalAttempts
                + " HTTP calls were retries (need at least 20%)");
        }

        List<String> violations = new ArrayList<>(failures);
        violations.addAll(describe(invariantChecker.check()));

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("logicalTransfers", logicals.size());
        notes.put("httpAttempts", totalAttempts);
        notes.put("retries", retries);
        notes.put("retryShare", Math.round(100.0 * retries / totalAttempts) / 100.0);
        notes.put("acked2xx", acked);
        notes.put("appliedWithoutAck", appliedWithoutAck);
        notes.put("notApplied", notApplied);
        notes.put("connectionDropWindows", dropWindows.get());
        notes.put("pgLatencyMs", "300 +/- 100");
        notes.put("sampleClientErrors", outcomes.values().stream()
            .map(Outcome::lastError).filter(java.util.Objects::nonNull)
            .map(e -> e.length() > 300 ? e.substring(0, 300) : e)
            .distinct().limit(5).toList());
        // Reconcile the scenario's day with both sources, faults off: 0 mismatches is part of passing.
        removeAllToxics(PG_PROXY);
        List<String> mismatches = reconcileToday();
        var file = ChaosResults.write("C3-db-latency-and-drops", startedAt,
            Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), totalAttempts, violations, mismatches, notes);
        log.info("c3.result file={} violations={} notes={}", file, violations.size(), notes);

        assertThat(violations).as("C3 violations").isEmpty();
        assertThat(mismatches).as("C3 reconciliation mismatches").isEmpty();
        assertThat(acked)
            .as("most transfers must succeed despite the faults; otherwise the API is just broken")
            .isGreaterThanOrEqualTo(logicals.size() / 2);
        assertThat(totalAttempts)
            .as("the faults must actually have forced retries, or this scenario proved nothing")
            .isGreaterThan(logicals.size());
    }

    /** One logical transfer: up to MAX_ATTEMPTS POSTs, all with the same Idempotency-Key and body. */
    private Outcome transferWithRetries(HttpClient http, Logical l) {
        String body = "{\"fromAccountId\":" + l.from() + ",\"toAccountId\":" + l.to()
            + ",\"amountPaise\":" + l.amount() + ",\"memo\":\"c3\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/api/v1/transfers"))
            .timeout(CLIENT_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", l.key())
            .header("X-Actor-Id", "1")
            .header("X-Actor-Role", "ADMIN")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        Integer lastStatus = null;
        String lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                lastStatus = response.statusCode();
                if (lastStatus >= 200 && lastStatus < 300) {
                    JsonNode node = json.readTree(response.body());
                    return new Outcome(attempt, lastStatus, node.path("entryId").asLong(), null);
                }
                lastError = response.body();
                boolean retryable = lastStatus >= 500 || lastStatus == 409 || lastStatus == 429;
                if (!retryable) {
                    return new Outcome(attempt, lastStatus, null, lastError);
                }
            } catch (IOException e) {
                lastStatus = null;
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(attempt, null, null, "interrupted");
            }
            sleep(250L * attempt);
        }
        return new Outcome(MAX_ATTEMPTS, lastStatus, null, lastError);
    }

    /** The one entry for this key must be exactly the transfer the client asked for. */
    private void checkPostings(Logical l, long entryId, List<String> failures) {
        Map<Long, Long> legs = new HashMap<>();
        jdbcClient.sql("SELECT account_id, amount FROM postings WHERE entry_id = ?")
            .param(entryId)
            .query((rs, n) -> legs.merge(rs.getLong(1), rs.getLong(2), Long::sum))
            .list();
        Map<Long, Long> expected = Map.of(l.from(), -l.amount(), l.to(), l.amount());
        if (!legs.equals(expected)) {
            failures.add(l.key() + " entry " + entryId + " has legs " + legs + ", expected " + expected);
        }
    }

    /**
     * Every 4-10s, cut the DB off for 2s. (At 300ms per round trip a transfer takes ~4s, so
     * windows every 1-4s killed every transaction and nothing could ever succeed.)
     * Cut the DB off for 2s: data stops and connections are closed after 1s. */
    private void dropConnections(AtomicBoolean running, AtomicInteger windows) {
        Random random = new Random(33);
        while (running.get()) {
            try {
                Thread.sleep(4_000 + random.nextInt(6_000));
                if (!running.get()) {
                    return;
                }
                PG_PROXY.toxics().timeout("pg-drop", ToxicDirection.UPSTREAM, 1_000);
                windows.incrementAndGet();
                Thread.sleep(2_000);
                PG_PROXY.toxics().get("pg-drop").remove();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("c3.monkey error={}", e.toString());
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
