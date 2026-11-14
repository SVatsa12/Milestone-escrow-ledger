package com.svatsa.provider.payouts;

import com.svatsa.provider.mode.Mode;
import com.svatsa.provider.mode.ModeState;
import com.svatsa.provider.payouts.ProviderPayout.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The provider's behaviour. One rule makes it safe: a key gets at most one row, ever (UNIQUE
 * index). Whoever inserts first decides what the key means; everybody else reads that row back.
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    /** Settlement days are Indian business days. */
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final String INVALID_ACCOUNT = "INVALID_ACCOUNT";

    private final PayoutRepository repository;
    private final ModeState modeState;
    private final Clock clock;
    private final PayoutRequestObserver observer;

    public PayoutService(PayoutRepository repository, ModeState modeState, Clock clock,
                         ObjectProvider<PayoutRequestObserver> observer) {
        this.repository = repository;
        this.modeState = modeState;
        this.clock = clock;
        // Test-only hook; absent in every real deployment.
        this.observer = observer.getIfAvailable(() -> PayoutRequestObserver.NONE);
    }

    /** An HTTP status and body. */
    public record Reply(HttpStatus status, Object body) {}

    /**
     * POST /v1/payouts. A key that already has a row is never processed again: the stored row is
     * answered with the status code that row means (see {@link #replay}).
     */
    public Reply requestPayout(String key, String beneficiary, long amountPaise) {
        observer.onPayoutRequest(key);
        var existing = repository.findByKey(key);
        if (existing.isPresent()) {
            return replay(existing.get());
        }

        Mode mode = modeState.next();
        long delayMs = modeState.current().delayMs();
        log.info("provider.payout-request key={} amountPaise={} mode={}", key, amountPaise, mode);
        try {
            return switch (mode) {
                case SUCCESS -> replay(insertSettled(key, beneficiary, amountPaise, Status.SUCCESS));
                case FAIL -> replay(insertSettled(key, beneficiary, amountPaise, Status.FAILED));
                case ERROR_500 -> new Reply(HttpStatus.INTERNAL_SERVER_ERROR, Map.of("error", "SIMULATED_500"));
                case TIMEOUT_THEN_SUCCEED -> {
                    ProviderPayout paid = insertSettled(key, beneficiary, amountPaise, Status.SUCCESS);
                    sleep(delayMs);
                    yield replay(paid);
                }
                case TIMEOUT_THEN_FAIL -> {
                    ProviderPayout failed = insertSettled(key, beneficiary, amountPaise, Status.FAILED);
                    sleep(delayMs);
                    yield replay(failed);
                }
                case TIMEOUT_NOT_RECEIVED -> {
                    sleep(delayMs);
                    yield new Reply(HttpStatus.GATEWAY_TIMEOUT, Map.of("error", "SIMULATED_REQUEST_LOST"));
                }
                case ASYNC -> {
                    Instant now = clock.instant();
                    Instant due = now.plusMillis(modeState.nextAsyncDelayMs());
                    Status outcome = modeState.nextAsyncSucceeds() ? Status.SUCCESS : Status.FAILED;
                    yield replay(repository.insert(key, beneficiary, amountPaise, Status.PROCESSING,
                        null, now, null, due, outcome));
                }
                case RANDOM -> throw new IllegalStateException("ModeState.next() never returns RANDOM");
            };
        } catch (DuplicateKeyException e) {
            // A concurrent POST or cancel with the same key inserted first: answer with its row.
            log.info("provider.payout-duplicate key={}", key);
            return replay(repository.findByKey(key).orElseThrow());
        }
    }

    /**
     * POST /v1/payouts/by-key/{key}/cancel. Absent: insert a CANCELLED tombstone so a late POST
     * is rejected. PROCESSING: cancel it. CANCELLED: 200 again. SUCCESS / FAILED: 409.
     */
    public Reply cancel(String key) {
        var existing = repository.findByKey(key);
        if (existing.isEmpty()) {
            try {
                ProviderPayout tombstone = repository.insert(key, null, null, Status.CANCELLED,
                    null, clock.instant(), null, null, null);
                log.info("provider.cancel key={} result=tombstone", key);
                return new Reply(HttpStatus.OK, tombstone);
            } catch (DuplicateKeyException e) {
                existing = repository.findByKey(key);   // a POST won the race; decide on its row
            }
        }
        ProviderPayout payout = existing.orElseThrow();
        if (payout.status() == Status.PROCESSING && repository.cancelIfProcessing(key)) {
            log.info("provider.cancel key={} result=cancelled-processing", key);
            return new Reply(HttpStatus.OK, repository.findByKey(key).orElseThrow());
        }
        // Either it wasn't PROCESSING, or the scheduler settled it just now: report what it is.
        ProviderPayout current = repository.findByKey(key).orElseThrow();
        if (current.status() == Status.CANCELLED) {
            return new Reply(HttpStatus.OK, current);
        }
        log.info("provider.cancel key={} result=conflict status={}", key, current.status());
        return new Reply(HttpStatus.CONFLICT, current);
    }

    public Reply findByKey(String key) {
        return repository.findByKey(key)
            .map(p -> new Reply(HttpStatus.OK, p))
            .orElseGet(() -> new Reply(HttpStatus.NOT_FOUND, Map.of("code", "NOT_FOUND", "key", key)));
    }

    /** SUCCESS payouts settled on {@code date}, an Asia/Kolkata calendar day. */
    public List<ProviderPayout> settlements(LocalDate date) {
        return repository.findSettled(date.atStartOfDay(IST).toInstant(), date.plusDays(1).atStartOfDay(IST).toInstant());
    }

    /** Settle every ASYNC payout that is due. Returns how many changed. */
    @Scheduled(fixedDelayString = "${provider.async-poll-ms:1000}")
    public int settleDueAsync() {
        Instant now = clock.instant();
        int settled = 0;
        for (var id : repository.findDueAsync(now)) {
            if (repository.settleAsync(id, now)) {
                settled++;
            }
        }
        if (settled > 0) {
            log.info("provider.async-settled count={}", settled);
        }
        return settled;
    }

    public void reset() {
        repository.truncate();
        log.info("provider.reset");
    }

    /** The status code a stored row means. Repeating a POST gets exactly the first answer. */
    static Reply replay(ProviderPayout p) {
        HttpStatus status = switch (p.status()) {
            case SUCCESS -> HttpStatus.OK;
            case FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PROCESSING -> HttpStatus.ACCEPTED;
            case CANCELLED -> HttpStatus.CONFLICT;
        };
        return new Reply(status, p);
    }

    private ProviderPayout insertSettled(String key, String beneficiary, long amountPaise, Status status) {
        Instant now = clock.instant();
        return repository.insert(key, beneficiary, amountPaise, status,
            status == Status.FAILED ? INVALID_ACCOUNT : null, now, now, null, null);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
