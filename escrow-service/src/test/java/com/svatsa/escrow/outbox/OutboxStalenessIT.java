package com.svatsa.escrow.outbox;

import com.svatsa.escrow.recon.Violation;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OutboxWriter stamps created_at from the injected Clock, and I9 measures staleness on the same
 * clock. Before, created_at came from System.currentTimeMillis() and I9 compared it with the
 * database's now(), so moving the test clock could never make a row stale.
 */
@DisplayName("Outbox staleness (I9) on the injected clock")
class OutboxStalenessIT extends AbstractIntegrationTest {

    @Autowired private OutboxWriter outboxWriter;
    @Autowired private TransactionTemplate tx;
    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("an unpublished row is reported by I9 once the clock is 6 minutes past it")
    void unpublishedRowBecomesStaleWhenTheClockMoves() throws Exception {
        Instant writtenAt = Instant.parse("2032-06-01T10:00:00.123456Z");
        clock.freezeAt(writtenAt);

        // The relay polls every 200 ms. Hold the row with FOR UPDATE: the relay claims with
        // SKIP LOCKED, so it leaves a locked row unpublished. If the relay wins the race between
        // our commit and our lock, write another row.
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            String eventId = null;
            for (int attempt = 0; attempt < 20 && eventId == null; attempt++) {
                String aggregateId = "i9-" + UUID.randomUUID();
                tx.executeWithoutResult(s -> outboxWriter.write("I9Test", aggregateId, "Stale",
                    "escrow.milestone-events", Map.of("note", "held unpublished by the test")));
                try (PreparedStatement lock = holder.prepareStatement(
                        "SELECT event_id::text FROM outbox WHERE aggregate_id = ? AND published_at IS NULL "
                            + "FOR UPDATE SKIP LOCKED")) {
                    lock.setString(1, aggregateId);
                    try (ResultSet rs = lock.executeQuery()) {
                        eventId = rs.next() ? rs.getString(1) : null;
                    }
                }
            }
            assertThat(eventId).as("could hold an unpublished outbox row").isNotNull();

            Instant createdAt = jdbcClient.sql("SELECT created_at FROM outbox WHERE event_id = ?::uuid")
                .param(eventId).query(java.time.OffsetDateTime.class).single().toInstant();
            assertThat(createdAt).as("created_at comes from the Clock").isEqualTo(writtenAt.truncatedTo(ChronoUnit.MICROS));
            assertThat(i9()).as("not stale yet").isEmpty();

            clock.advance(Duration.ofMinutes(4));
            assertThat(i9()).as("4 minutes is not stale").isEmpty();

            clock.advance(Duration.ofMinutes(2));
            assertThat(i9()).as("6 minutes after it was written, the unpublished row is stale")
                .singleElement()
                .satisfies(v -> assertThat(v.detail()).contains("unpublished outbox rows older than 5 minutes"));

            holder.rollback(); // release the row; the relay publishes it
        }
    }

    private List<Violation> i9() {
        return invariantChecker.check().stream().filter(v -> v.code().startsWith("I9")).toList();
    }
}
