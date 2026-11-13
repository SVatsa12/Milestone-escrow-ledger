package com.svatsa.escrow.payouts;

import com.svatsa.escrow.chaos.ChaosConfig;
import com.svatsa.escrow.common.DomainException;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import com.svatsa.escrow.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Payout saga.
 *
 * <ol>
 *   <li>T1 (committed on its own): create the payout in RESERVED and post
 *       FREELANCER_WALLET to PAYOUT_IN_FLIGHT.</li>
 *   <li>Outside any transaction: call the provider once. Never retried here.</li>
 *   <li>T2: confirm (PAYOUT_IN_FLIGHT to EXTERNAL_BANK:PAYOUTS), reverse
 *       (PAYOUT_IN_FLIGHT to FREELANCER_WALLET), or park the payout in PROCESSING / UNKNOWN
 *       for {@link PayoutRecoveryJob}.</li>
 * </ol>
 *
 * Transactions are explicit ({@link TransactionTemplate}) rather than {@code @Transactional}
 * because these methods call each other: a self-invoked {@code @Transactional} method gets
 * no proxy, so T1 would not commit before the provider call and confirm/reverse would not be
 * atomic. (Found while building chaos scenario C2, see chaos/FINDINGS.md.)
 */
@Service
public class PayoutService {
    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final PayoutRepository payoutRepository;
    private final LedgerService ledgerService;
    private final OutboxWriter outboxWriter;
    private final PayoutProviderClient providerClient;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PayoutService(
            PayoutRepository payoutRepository,
            LedgerService ledgerService,
            OutboxWriter outboxWriter,
            PayoutProviderClient providerClient,
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.payoutRepository = payoutRepository;
        this.ledgerService = ledgerService;
        this.outboxWriter = outboxWriter;
        this.providerClient = providerClient;
        this.jdbcClient = jdbcClient;
        this.tx = transactionTemplate;
        this.clock = clock;
    }

    /**
     * Request a payout. Idempotent per (freelancerId, idempotencyKey): a repeat returns the
     * existing payout and never calls the provider again.
     *
     * Must not be called inside an existing transaction: T1 has to be durable before the
     * provider is told to move money.
     */
    public Payout requestPayout(long freelancerId, long walletAccountId, long amountPaise, String idempotencyKey) {
        record Reserved(Payout payout, boolean fresh) {}

        Reserved reserved = tx.execute(status -> {
            var existing = jdbcClient.sql(
                    "SELECT payout_id FROM payout_idempotency WHERE freelancer_id = ? AND idempotency_key = ?")
                .param(freelancerId)
                .param(idempotencyKey)
                .query((rs, rowNum) -> rs.getObject(1, UUID.class))
                .optional();
            if (existing.isPresent()) {
                return new Reserved(findOrFail(existing.get()), false);
            }

            UUID payoutId = UUID.randomUUID();

            // Post FIRST, then insert the payout row. LedgerService.post takes FOR UPDATE locks in
            // ascending account id order (invariant 7). Inserting the payout row first would make
            // its FK check take a KEY SHARE lock on the wallet before that ordered locking, and two
            // concurrent payouts deadlock: A holds wallet(KEY SHARE) and waits on PAYOUT_IN_FLIGHT,
            // B holds PAYOUT_IN_FLIGHT and waits on wallet(FOR UPDATE). Found by chaos C2.
            // INSUFFICIENT_FUNDS propagates and rolls back the whole transaction.
            ledgerService.post(new PostingRequest(
                "PAYOUT_RESERVE",
                "PAYOUT",
                payoutId.toString(),
                "payout:reserve:" + payoutId,
                "Reserve funds for payout",
                List.of(
                    new Leg(walletAccountId, -amountPaise),
                    new Leg(accountId("PAYOUT_IN_FLIGHT"), amountPaise)
                )
            ));

            Payout payout = payoutRepository.createReserved(payoutId, freelancerId, walletAccountId, amountPaise);

            jdbcClient.sql(
                    "INSERT INTO payout_idempotency (payout_id, freelancer_id, idempotency_key) VALUES (?, ?, ?)")
                .param(payoutId)
                .param(freelancerId)
                .param(idempotencyKey)
                .update();

            outboxWriter.write("PAYOUT", payoutId.toString(), "PayoutReserved", "escrow.payment-events",
                event(payoutId, amountPaise, Map.of("freelancerId", freelancerId)));
            return new Reserved(payout, true);
        });

        if (!reserved.fresh()) {
            return reserved.payout();
        }

        // T1 is committed. A crash here leaves a RESERVED row for the recovery job.
        ChaosConfig.crashIfNeeded(ChaosConfig.CrashPoint.AFTER_RESERVE);

        handleProviderCall(reserved.payout());
        return findOrFail(reserved.payout().id());
    }

    /**
     * Call the provider exactly once and act on the classification.
     */
    private void handleProviderCall(Payout payout) {
        PayoutProviderClient.ProviderResponse response;
        try {
            response = providerClient.requestPayout(payout.id(), "freelancer-" + payout.freelancerId(), payout.amountPaise());
        } catch (RuntimeException e) {
            log.error("payout.provider-call-unexpected payoutId={}", payout.id(), e);
            response = null;
        }

        // Provider may have paid; a crash here leaves a RESERVED row whose status check says SUCCESS.
        ChaosConfig.crashIfNeeded(ChaosConfig.CrashPoint.AFTER_PROVIDER_CALL);

        if (response == null) {
            park(payout, PayoutState.UNKNOWN, 15, "Provider call timed out or errored");
            return;
        }
        int status = response.statusCode();
        if (status == 200) {
            confirmPayout(payout);
        } else if (status == 202) {
            park(payout, PayoutState.PROCESSING, 10, "Provider returned 202 PROCESSING");
        } else if (status == 409) {
            // Key is tombstoned at the provider: it will never pay this one.
            reversePayout(payout, PayoutState.CANCELLED, "CANCELLED", "Provider says key is cancelled");
        } else if (status >= 400 && status < 500) {
            reversePayout(payout, PayoutState.FAILED, "FAILED", "Provider rejected: " + status);
        } else {
            park(payout, PayoutState.UNKNOWN, 15, "Provider returned " + status);
        }
    }

    /**
     * T2 confirm: PAYOUT_IN_FLIGHT to EXTERNAL_BANK:PAYOUTS. A no-op (returns false) if the
     * payout was already settled by someone else.
     */
    public boolean confirmPayout(Payout payout) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (payoutRepository.settle(payout.id(), PayoutState.CONFIRMED, "SUCCESS", "Provider SUCCESS").isEmpty()) {
                log.info("payout.confirm-skipped payoutId={} reason=already-settled", payout.id());
                return false;
            }
            ledgerService.post(new PostingRequest(
                "PAYOUT_CONFIRM",
                "PAYOUT",
                payout.id().toString(),
                "payout:confirm:" + payout.id(),
                "Confirm payout to external bank",
                List.of(
                    new Leg(accountId("PAYOUT_IN_FLIGHT"), -payout.amountPaise()),
                    new Leg(accountId("EXTERNAL_BANK:PAYOUTS"), payout.amountPaise())
                )
            ));
            outboxWriter.write("PAYOUT", payout.id().toString(), "PayoutConfirmed", "escrow.payment-events",
                event(payout.id(), payout.amountPaise(), Map.of()));
            return true;
        }));
    }

    /**
     * T2 reverse: PAYOUT_IN_FLIGHT to FREELANCER_WALLET, ending in FAILED or CANCELLED.
     * A no-op (returns false) if the payout was already settled.
     */
    public boolean reversePayout(Payout payout, PayoutState target, String providerStatus, String reason) {
        if (target != PayoutState.FAILED && target != PayoutState.CANCELLED) {
            throw new IllegalArgumentException("reverse target must be FAILED or CANCELLED: " + target);
        }
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (payoutRepository.settle(payout.id(), target, providerStatus, reason).isEmpty()) {
                log.info("payout.reverse-skipped payoutId={} reason=already-settled", payout.id());
                return false;
            }
            boolean cancelled = target == PayoutState.CANCELLED;
            ledgerService.post(new PostingRequest(
                cancelled ? "PAYOUT_CANCEL" : "PAYOUT_REVERSE",
                "PAYOUT",
                payout.id().toString(),
                "payout:reverse:" + payout.id(),
                cancelled ? "Cancel payout" : "Reverse failed payout",
                List.of(
                    new Leg(accountId("PAYOUT_IN_FLIGHT"), -payout.amountPaise()),
                    new Leg(payout.walletAccountId(), payout.amountPaise())
                )
            ));
            outboxWriter.write("PAYOUT", payout.id().toString(), cancelled ? "PayoutCancelled" : "PayoutFailed",
                "escrow.payment-events", event(payout.id(), payout.amountPaise(), Map.of("reason", reason)));
            return true;
        }));
    }

    /**
     * Move a freshly reserved payout to PROCESSING or UNKNOWN and schedule a status check.
     * Only from RESERVED: if the recovery job got there first, leave its result alone.
     */
    private void park(Payout payout, PayoutState target, long checkInSeconds, String reason) {
        tx.executeWithoutResult(status -> {
            int updated = payoutRepository.transitionState(payout.id(), PayoutState.RESERVED, target, reason);
            if (updated > 0) {
                Instant nextCheck = clock.instant().plusSeconds(checkInSeconds);
                payoutRepository.claimForRecovery(payout.id(), nextCheck);
                if (target == PayoutState.UNKNOWN) {
                    outboxWriter.write("PAYOUT", payout.id().toString(), "PayoutUnknown", "escrow.payment-events",
                        event(payout.id(), payout.amountPaise(), Map.of("reason", reason)));
                }
            }
        });
    }

    private Payout findOrFail(UUID id) {
        return payoutRepository.findById(id)
            .orElseThrow(() -> new DomainException("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR,
                "Payout not found: " + id));
    }

    private Map<String, Object> event(UUID payoutId, long amountPaise, Map<String, Object> extra) {
        Map<String, Object> payload = new HashMap<>(extra);
        payload.put("payoutId", payoutId.toString());
        payload.put("amountPaise", amountPaise);
        return payload;
    }

    private long accountId(String code) {
        return jdbcClient.sql("SELECT id FROM accounts WHERE code = ?")
            .param(code)
            .query(Long.class)
            .optional()
            .orElseThrow(() -> new DomainException("ACCOUNT_MISSING", HttpStatus.INTERNAL_SERVER_ERROR,
                code + " account not found"));
    }
}
