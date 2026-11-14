package com.svatsa.escrow.outbox;

import com.svatsa.escrow.chaos.AbstractToxiproxyIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The P15 benchmark left ~29k outbox rows unpublished for over 5 minutes: the relay sent one
 * event at a time (send().get() + an UPDATE per row, 200ms pause per batch), a few hundred
 * events/s at best. It now sends a batch asynchronously, marks it in one UPDATE, and keeps
 * draining while batches are full.
 */
@DisplayName("Outbox relay keeps up with a burst")
class OutboxRelayThroughputIT extends AbstractToxiproxyIT {

    private static final int EVENTS = 5_000;

    @Autowired private OutboxWriter outboxWriter;
    @Autowired private TransactionTemplate tx;

    @Test
    void drainsABurstOfFiveThousandEventsQuickly() {
        String aggregate = "burst-" + UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < EVENTS; i++) {
                outboxWriter.write("Burst", aggregate, "BurstEvent", "ledger.entry-events", Map.of("n", i));
            }
        });

        long start = System.nanoTime();
        // The old relay needed ~20s for this; 10s leaves room for a slow CI box.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
            assertThat(jdbcClient.sql("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND published_at IS NULL")
                .param(aggregate).query(Long.class).single()).isZero());
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        System.out.printf("outbox relay drained %d events in %d ms (%.0f events/s)%n", EVENTS, ms, EVENTS * 1000.0 / Math.max(ms, 1));
    }
}
