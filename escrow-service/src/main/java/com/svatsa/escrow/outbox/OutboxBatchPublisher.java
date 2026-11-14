package com.svatsa.escrow.outbox;

import com.svatsa.escrow.outbox.OutboxRelay.OutboxRow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decides, for one claimed batch, which outbox rows may be marked published. Pure logic over a
 * {@link Sender}, so its rules are unit-tested against a failing producer.
 *
 * <ol>
 *   <li><b>Ack before mark.</b> A row is in {@link Result#published()} only if ITS OWN send
 *       completed successfully before the deadline. A send still pending at the deadline counts as
 *       failed (it may still be delivered; the row is re-sent later, which is at-least-once).</li>
 *   <li><b>Per-aggregate prefix.</b> Rows of one aggregate (the Kafka key) are handled in id order,
 *       and only the prefix before that aggregate's first failure is marked. A later row of the same
 *       aggregate is never marked ahead of an earlier failed one: it is held back unmarked (even if
 *       its own send succeeded) and goes again after the failed row on a later poll (claims are
 *       ORDER BY id, so the failed row comes first).</li>
 *   <li><b>Synchronous failures stop the aggregate.</b> If {@code send} throws (the row can't be
 *       built or serialized), the aggregate's later rows in the batch are not sent at all.</li>
 * </ol>
 *
 * Limit: sends are asynchronous, so when a failure is only reported later (broker timeout), a
 * later row of the same aggregate may already be on Kafka. It is not marked, so it is re-sent
 * after the failed row, and consumers dedupe by eventId; but a consumer can see that later event
 * before the failed one's retry. Closing that gap means waiting for each ack before sending the
 * aggregate's next row, which serializes a busy aggregate (see chaos/FINDINGS.md #42).
 */
final class OutboxBatchPublisher {

    /** Starts the send of one row. May throw (a synchronous failure). */
    @FunctionalInterface
    interface Sender {
        CompletableFuture<?> send(OutboxRow row) throws Exception;
    }

    /**
     * @param published rows whose own send was acknowledged and that follow no failure of their
     *                  aggregate in this batch, in batch order
     * @param failed    rows whose send failed or timed out (count as a delivery attempt)
     * @param heldBack  rows not marked because an earlier row of their aggregate failed (not sent,
     *                  or sent but deliberately left unmarked); not an attempt
     */
    record Result(List<OutboxRow> published, Map<OutboxRow, Exception> failed, List<OutboxRow> heldBack) {}

    private OutboxBatchPublisher() {
    }

    static String aggregateKey(OutboxRow row) {
        return row.aggregateType() + ":" + row.aggregateId();
    }

    static Result publish(List<OutboxRow> rows, Sender sender, Duration ackTimeout) {
        int n = rows.size();
        @SuppressWarnings("unchecked")
        CompletableFuture<?>[] sends = new CompletableFuture<?>[n];
        Exception[] errors = new Exception[n];
        Set<String> stoppedBeforeSend = new HashSet<>();

        // 1. Send in batch (id) order. A synchronous failure stops that aggregate for this batch.
        for (int i = 0; i < n; i++) {
            String key = aggregateKey(rows.get(i));
            if (stoppedBeforeSend.contains(key)) {
                continue;   // not sent: held back below
            }
            try {
                sends[i] = sender.send(rows.get(i));
            } catch (Exception e) {
                errors[i] = e;
                stoppedBeforeSend.add(key);
            }
        }

        // 2. Wait for every ack against one deadline (the sends are in flight together).
        long deadline = System.nanoTime() + ackTimeout.toNanos();
        for (int i = 0; i < n; i++) {
            if (sends[i] == null) {
                continue;
            }
            try {
                sends[i].get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (ExecutionException e) {
                errors[i] = e.getCause() instanceof Exception cause ? cause : e;
            } catch (TimeoutException e) {
                errors[i] = new TimeoutException("no ack within " + ackTimeout.toMillis() + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors[i] = e;
            }
        }

        // 3. Mark only each aggregate's prefix before its first failure.
        List<OutboxRow> published = new ArrayList<>();
        Map<OutboxRow, Exception> failed = new LinkedHashMap<>();
        List<OutboxRow> heldBack = new ArrayList<>();
        Set<String> broken = new HashSet<>();
        for (int i = 0; i < n; i++) {
            OutboxRow row = rows.get(i);
            String key = aggregateKey(row);
            if (errors[i] != null) {
                failed.put(row, errors[i]);
                broken.add(key);
            } else if (sends[i] == null || broken.contains(key)) {
                heldBack.add(row);
            } else {
                published.add(row);
            }
        }
        return new Result(published, failed, heldBack);
    }
}
