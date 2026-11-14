package com.svatsa.escrow.recon;

import com.svatsa.escrow.deposits.RazorpayGateway;
import com.svatsa.escrow.ledger.LedgerKeys;
import com.svatsa.escrow.payouts.Payout;
import com.svatsa.escrow.payouts.PayoutProviderClient;
import com.svatsa.escrow.payouts.PayoutProviderClient.ProviderSettlement;
import com.svatsa.escrow.payouts.PayoutRepository;
import com.svatsa.escrow.payouts.PayoutState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Daily reconciliation of our books against the outside world. It NEVER moves money: it only
 * records mismatches for a human to act on.
 *
 * <h2>Sources</h2>
 * <ul>
 *   <li><b>PAYOUT_PROVIDER</b> (run entity_type PAYOUT): our payouts CONFIRMED that day against the
 *       provider's GET /v1/settlements (its SUCCESS payouts settled that day).
 *       <ul>
 *         <li>MISSING_AT_PROVIDER: we CONFIRMED, the provider has no SUCCESS for the key.</li>
 *         <li>MISSING_IN_LEDGER: the provider paid, we have no CONFIRMED payout for the key. The
 *             detail names our state: UNKNOWN or PROCESSING is recovery lag; FAILED or CANCELLED
 *             means the bank paid AND we refunded the wallet, a real double payout.</li>
 *         <li>AMOUNT_MISMATCH: same key, different amounts.</li>
 *       </ul></li>
 *   <li><b>RAZORPAY</b> (run entity_type DEPOSIT): our deposits CAPTURED that day against
 *       {@link RazorpayGateway#fetchPayments} with status captured.
 *       <ul>
 *         <li>MISSING_IN_LEDGER: captured at Razorpay, never credited (no deposit:rzp:{paymentId}
 *             ledger entry): a missed webhook.</li>
 *         <li>MISSING_AT_GATEWAY: we credited it, Razorpay has no captured payment for it.</li>
 *         <li>AMOUNT_MISMATCH.</li>
 *       </ul></li>
 * </ul>
 * Each run also runs {@link InvariantChecker} and records every violation as LEDGER_INVARIANT.
 *
 * <h2>Days and the midnight edge</h2>
 * A day is an Asia/Kolkata calendar day. Both sides can put the same payout on different days:
 * the provider settles at 23:59 IST and we confirm (after a timeout and the recovery job) at
 * 00:01 the next day. Matching only within one day would report it twice, MISSING_IN_LEDGER on
 * the first day and MISSING_AT_PROVIDER on the second. So keys are matched over a ±1 day window
 * and every pair is attributed to the EXTERNAL side's day (the provider's settlement day, or the
 * Razorpay payment's day): that day's run compares amounts, and our side's day ignores it. Only an
 * item with no counterpart anywhere in the window is "missing".
 *
 * <h2>Re-runs</h2>
 * A run is per (date, source) and a re-run replaces that run's mismatches in one transaction, so
 * the run always shows what is wrong now. External data is fetched first, outside any transaction.
 *
 * <h2>Metrics</h2>
 * {@code escrow.recon.mismatches{source, reason}}: the mismatch counts of the latest run per
 * source. {@code escrow.recon.runs{source, status}}: runs completed or failed.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static final String SOURCE_PAYOUT_PROVIDER = "PAYOUT_PROVIDER";
    public static final String SOURCE_RAZORPAY = "RAZORPAY";

    public static final String MISSING_AT_PROVIDER = "MISSING_AT_PROVIDER";
    public static final String MISSING_AT_GATEWAY = "MISSING_AT_GATEWAY";
    public static final String MISSING_IN_LEDGER = "MISSING_IN_LEDGER";
    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String LEDGER_INVARIANT = "LEDGER_INVARIANT";

    private static final Map<String, List<String>> REASONS = Map.of(
        SOURCE_PAYOUT_PROVIDER, List.of(MISSING_AT_PROVIDER, MISSING_IN_LEDGER, AMOUNT_MISMATCH, LEDGER_INVARIANT),
        SOURCE_RAZORPAY, List.of(MISSING_AT_GATEWAY, MISSING_IN_LEDGER, AMOUNT_MISMATCH, LEDGER_INVARIANT));

    private final ReconciliationRepository reconRepo;
    private final PayoutRepository payoutRepo;
    private final PayoutProviderClient providerClient;
    private final RazorpayGateway razorpayGateway;
    private final InvariantChecker invariantChecker;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public ReconciliationService(
        ReconciliationRepository reconRepo,
        PayoutRepository payoutRepo,
        PayoutProviderClient providerClient,
        RazorpayGateway razorpayGateway,
        InvariantChecker invariantChecker,
        JdbcClient jdbc,
        TransactionTemplate tx,
        MeterRegistry meterRegistry,
        Clock clock
    ) {
        this.reconRepo = reconRepo;
        this.payoutRepo = payoutRepo;
        this.providerClient = providerClient;
        this.razorpayGateway = razorpayGateway;
        this.invariantChecker = invariantChecker;
        this.jdbc = jdbc;
        this.tx = tx;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        REASONS.forEach((source, reasons) -> reasons.forEach(reason -> gauge(source, reason)));
    }

    /** Both sources for one Asia/Kolkata day. One failing doesn't stop the other. */
    public void run(LocalDate date) {
        log.info("recon.start date={}", date);
        try {
            reconcilePayouts(date);
        } catch (Exception e) {
            log.error("recon.failed source={} date={}", SOURCE_PAYOUT_PROVIDER, date, e);
        }
        try {
            reconcileDeposits(date);
        } catch (Exception e) {
            log.error("recon.failed source={} date={}", SOURCE_RAZORPAY, date, e);
        }
    }

    // --- PAYOUT_PROVIDER ---------------------------------------------------------------------------

    /** Payouts vs the provider's settlements for {@code date}. Returns the mismatches recorded. */
    public List<ReconMismatch> reconcilePayouts(LocalDate date) {
        ReconRun run = reconRepo.getOrCreateRun(date, "PAYOUT");
        try {
            // Theirs: settled on the day itself, and over the ±1 day window for matching.
            Map<String, ProviderSettlement> theirsOnDay = byKey(providerClient.settlements(date), ProviderSettlement::idempotencyKey);
            Map<String, ProviderSettlement> theirsWindow = new HashMap<>(theirsOnDay);
            theirsWindow.putAll(byKey(providerClient.settlements(date.minusDays(1)), ProviderSettlement::idempotencyKey));
            theirsWindow.putAll(byKey(providerClient.settlements(date.plusDays(1)), ProviderSettlement::idempotencyKey));

            List<ReconMismatch> found = new ArrayList<>();

            // Everything the provider paid that day must be a CONFIRMED payout of the same amount.
            for (ProviderSettlement theirs : theirsOnDay.values()) {
                Optional<Payout> ours = payoutFor(theirs.idempotencyKey());
                if (ours.isEmpty() || ours.get().state() != PayoutState.CONFIRMED) {
                    String state = ours.map(p -> p.state().name()).orElse("ABSENT");
                    found.add(mismatch(run, SOURCE_PAYOUT_PROVIDER, "PAYOUT", theirs.idempotencyKey(), state, "SUCCESS",
                        ours.map(Payout::amountPaise).orElse(null), theirs.amountPaise(), MISSING_IN_LEDGER,
                        missingInLedgerDetail(state)));
                } else if (ours.get().amountPaise() != theirs.amountPaise()) {
                    found.add(mismatch(run, SOURCE_PAYOUT_PROVIDER, "PAYOUT", theirs.idempotencyKey(), "CONFIRMED", "SUCCESS",
                        ours.get().amountPaise(), theirs.amountPaise(), AMOUNT_MISMATCH,
                        "we confirmed " + ours.get().amountPaise() + " paise, the provider paid " + theirs.amountPaise()));
                }
            }

            // Everything we confirmed that day must have been paid within the window. A pair whose
            // settlement is on the next or previous day belongs to that day's run.
            for (Payout ours : confirmedPayouts(date)) {
                if (!theirsWindow.containsKey(ours.id().toString())) {
                    found.add(mismatch(run, SOURCE_PAYOUT_PROVIDER, "PAYOUT", ours.id().toString(), "CONFIRMED",
                        providerStatus(ours.id()), ours.amountPaise(), null, MISSING_AT_PROVIDER,
                        "confirmed by us; no SUCCESS settlement at the provider from " + date.minusDays(1) + " to " + date.plusDays(1)));
                }
            }
            return complete(run, SOURCE_PAYOUT_PROVIDER, found);
        } catch (RuntimeException e) {
            fail(run, SOURCE_PAYOUT_PROVIDER, e);
            throw e;
        }
    }

    private static String missingInLedgerDetail(String ourState) {
        return switch (ourState) {
            case "FAILED", "CANCELLED" -> "DOUBLE PAYOUT: the provider paid, and our payout is " + ourState
                + " so the wallet was refunded";
            case "ABSENT" -> "the provider paid a key we have no payout for";
            default -> "the provider paid; our payout is still " + ourState + " (recovery should confirm it)";
        };
    }

    private Optional<Payout> payoutFor(String key) {
        try {
            return payoutRepo.findById(UUID.fromString(key));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();   // not one of our payout ids at all
        }
    }

    private String providerStatus(UUID payoutId) {
        var status = providerClient.checkStatus(payoutId);
        if (status == null) {
            return "UNREACHABLE";
        }
        return status.payout() == null ? "NOT_FOUND" : status.payout().status();
    }

    private List<Payout> confirmedPayouts(LocalDate date) {
        List<UUID> ids = jdbc.sql("SELECT id FROM payouts WHERE state = 'CONFIRMED' AND updated_at >= ? AND updated_at < ?")
            .param(ts(startOf(date)))
            .param(ts(startOf(date.plusDays(1))))
            .query(UUID.class)
            .list();
        return ids.stream().map(id -> payoutRepo.findById(id).orElseThrow()).toList();
    }

    // --- RAZORPAY ----------------------------------------------------------------------------------

    /** Deposits vs Razorpay's captured payments for {@code date}. Returns the mismatches recorded. */
    public List<ReconMismatch> reconcileDeposits(LocalDate date) {
        ReconRun run = reconRepo.getOrCreateRun(date, "DEPOSIT");
        try {
            Instant dayStart = startOf(date);
            Instant dayEnd = startOf(date.plusDays(1));
            Map<String, RazorpayGateway.PaymentRecord> theirsWindow = byKey(
                razorpayGateway.fetchPayments(startOf(date.minusDays(1)), startOf(date.plusDays(2))).stream()
                    .filter(p -> "captured".equalsIgnoreCase(p.status()))
                    .toList(),
                RazorpayGateway.PaymentRecord::paymentId);

            List<ReconMismatch> found = new ArrayList<>();

            for (RazorpayGateway.PaymentRecord theirs : theirsWindow.values()) {
                if (theirs.createdAt().isBefore(dayStart) || !theirs.createdAt().isBefore(dayEnd)) {
                    continue;   // another day's run owns it
                }
                Optional<DepositRow> ours = depositFor(theirs);
                if (!credited(theirs.paymentId())) {
                    String detail = ours
                        .map(d -> "deposit " + d.id() + " is " + d.status() + "; no ledger entry "
                            + LedgerKeys.deposit(theirs.paymentId()) + " (missed webhook?)")
                        .orElse("no deposit for order " + theirs.orderId());
                    found.add(mismatch(run, SOURCE_RAZORPAY, "DEPOSIT", theirs.paymentId(),
                        ours.map(DepositRow::status).orElse("ABSENT"), "captured",
                        ours.map(DepositRow::amountPaise).orElse(null), theirs.amountPaise(), MISSING_IN_LEDGER, detail));
                } else if (ours.isPresent() && ours.get().amountPaise() != theirs.amountPaise()) {
                    found.add(mismatch(run, SOURCE_RAZORPAY, "DEPOSIT", theirs.paymentId(), ours.get().status(), "captured",
                        ours.get().amountPaise(), theirs.amountPaise(), AMOUNT_MISMATCH,
                        "deposit " + ours.get().id() + " credited " + ours.get().amountPaise()
                            + " paise, Razorpay captured " + theirs.amountPaise()));
                }
            }

            for (DepositRow ours : capturedDeposits(date)) {
                if (!theirsWindow.containsKey(ours.paymentId())) {
                    found.add(mismatch(run, SOURCE_RAZORPAY, "DEPOSIT", ours.paymentId(), "CAPTURED", "NOT_CAPTURED",
                        ours.amountPaise(), null, MISSING_AT_GATEWAY,
                        "deposit " + ours.id() + " was credited; Razorpay has no captured payment from "
                            + date.minusDays(1) + " to " + date.plusDays(1)));
                }
            }
            return complete(run, SOURCE_RAZORPAY, found);
        } catch (RuntimeException e) {
            fail(run, SOURCE_RAZORPAY, e);
            throw e;
        }
    }

    private record DepositRow(long id, long amountPaise, String paymentId, String status) {}

    private Optional<DepositRow> depositFor(RazorpayGateway.PaymentRecord payment) {
        return jdbc.sql("""
                SELECT id, amount_paise, razorpay_payment_id, status FROM deposits
                WHERE razorpay_payment_id = ? OR razorpay_order_id = ?
                ORDER BY (razorpay_payment_id = ?) DESC NULLS LAST
                LIMIT 1
                """)
            .param(payment.paymentId())
            .param(payment.orderId())
            .param(payment.paymentId())
            .query((rs, n) -> new DepositRow(rs.getLong("id"), rs.getLong("amount_paise"),
                rs.getString("razorpay_payment_id"), rs.getString("status")))
            .optional();
    }

    /** Credited means the ledger has the deposit's business-key entry (invariant 8), not just a status. */
    private boolean credited(String paymentId) {
        return jdbc.sql("SELECT count(*) FROM journal_entries WHERE idempotency_key = ?")
            .param(LedgerKeys.deposit(paymentId))
            .query(Long.class)
            .single() > 0;
    }

    private List<DepositRow> capturedDeposits(LocalDate date) {
        return jdbc.sql("""
                SELECT id, amount_paise, razorpay_payment_id, status FROM deposits
                WHERE status = 'CAPTURED' AND captured_at >= ? AND captured_at < ?
                ORDER BY id
                """)
            .param(ts(startOf(date)))
            .param(ts(startOf(date.plusDays(1))))
            .query((rs, n) -> new DepositRow(rs.getLong("id"), rs.getLong("amount_paise"),
                rs.getString("razorpay_payment_id"), rs.getString("status")))
            .list();
    }

    // --- shared ------------------------------------------------------------------------------------

    /** Adds LEDGER_INVARIANT, replaces the run's mismatches, completes the run, updates metrics. */
    private List<ReconMismatch> complete(ReconRun run, String source, List<ReconMismatch> found) {
        List<ReconMismatch> all = new ArrayList<>(found);
        List<Violation> violations = invariantChecker.check();
        for (int i = 0; i < violations.size(); i++) {
            Violation v = violations.get(i);
            all.add(new ReconMismatch(UUID.randomUUID(), run.id(), v.code() + "#" + (i + 1), "LEDGER",
                null, null, null, null, LEDGER_INVARIANT, v.code().endsWith("_WARN") ? "WARN" : "ERROR",
                "MANUAL_REVIEW_REQUIRED", clock.instant(), "LEDGER", v.code() + ": " + v.detail()));
        }
        tx.executeWithoutResult(s -> {
            reconRepo.replaceMismatches(run.id(), all);
            reconRepo.markCompleted(run.id(), all.size(), summary(source, all));
        });

        Map<String, Long> counts = all.stream().collect(Collectors.groupingBy(ReconMismatch::mismatchReason, Collectors.counting()));
        REASONS.get(source).forEach(reason -> gauge(source, reason).set(counts.getOrDefault(reason, 0L)));
        meterRegistry.counter("escrow.recon.runs", "source", source, "status", "COMPLETED").increment();
        log.info("recon.completed source={} date={} mismatches={} byReason={}", source, run.runDate(), all.size(), counts);
        return all;
    }

    private void fail(ReconRun run, String source, Exception e) {
        log.error("recon.run-failed source={} date={} error={}", source, run.runDate(), e.toString());
        reconRepo.markFailed(run.id(), source + ": " + e.getMessage());
        meterRegistry.counter("escrow.recon.runs", "source", source, "status", "FAILED").increment();
    }

    private static String summary(String source, List<ReconMismatch> all) {
        Map<String, Long> counts = new LinkedHashMap<>();
        all.forEach(m -> counts.merge(m.mismatchReason(), 1L, Long::sum));
        return source + " " + (counts.isEmpty() ? "clean" : counts.toString());
    }

    private ReconMismatch mismatch(ReconRun run, String source, String entityType, String entityId,
                                   String expectedState, String actualState, Long expectedAmount, Long actualAmount,
                                   String reason, String detail) {
        log.warn("recon.mismatch source={} reason={} entity={} detail={}", source, reason, entityId, detail);
        return new ReconMismatch(UUID.randomUUID(), run.id(), entityId, entityType, expectedState, actualState,
            expectedAmount, actualAmount, reason, "ERROR", "MANUAL_REVIEW_REQUIRED", clock.instant(), source, detail);
    }

    private AtomicLong gauge(String source, String reason) {
        return gauges.computeIfAbsent(source + "/" + reason, k -> meterRegistry.gauge("escrow.recon.mismatches",
            Tags.of("source", source, "reason", reason), new AtomicLong()));
    }

    private static <T> Map<String, T> byKey(List<T> items, Function<T, String> key) {
        Map<String, T> map = new HashMap<>();
        items.forEach(i -> map.put(key.apply(i), i));
        return map;
    }

    static Instant startOf(LocalDate date) {
        return date.atStartOfDay(IST).toInstant();
    }

    private static java.time.OffsetDateTime ts(Instant instant) {
        return com.svatsa.escrow.common.SqlTime.ts(instant);
    }

    /** Provider payout status -> our PayoutState name. */
    static String toInternalState(String providerStatus) {
        if (providerStatus == null) {
            return null;
        }
        return switch (providerStatus) {
            case "SUCCESS" -> "CONFIRMED";
            default -> providerStatus;
        };
    }
}
