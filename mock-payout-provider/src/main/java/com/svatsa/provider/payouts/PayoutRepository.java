package com.svatsa.provider.payouts;

import com.svatsa.provider.payouts.ProviderPayout.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * provider.payouts. Every write is a single auto-committed statement. The UNIQUE index on
 * idempotency_key is the only concurrency control needed: an insert for a key that already
 * exists throws DuplicateKeyException, and every state change is conditional on the current
 * status, so a POST, a cancel and the ASYNC scheduler can never both "win" the same key.
 */
@Repository
public class PayoutRepository {

    private static final String COLUMNS =
        "id, idempotency_key, beneficiary, amount_paise, status, failure_reason, created_at, settled_at";

    private final JdbcClient jdbc;

    public PayoutRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProviderPayout> findByKey(String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM provider.payouts WHERE idempotency_key = ?")
            .param(key)
            .query(PayoutRepository::map)
            .optional();
    }

    /** Insert a new row. Throws DuplicateKeyException if the key already has one. */
    public ProviderPayout insert(String key, String beneficiary, Long amountPaise, Status status,
                                 String failureReason, Instant createdAt, Instant settledAt,
                                 Instant asyncDueAt, Status asyncOutcome) {
        UUID id = UUID.randomUUID();
        // Postgres keeps microseconds. Truncate first, so the record returned now is identical to
        // the one a repeat request reads back (same key, same response).
        createdAt = micros(createdAt);
        settledAt = micros(settledAt);
        jdbc.sql("""
                INSERT INTO provider.payouts (id, idempotency_key, beneficiary, amount_paise, status,
                    failure_reason, created_at, settled_at, async_due_at, async_outcome)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .param(id)
            .param(key)
            .param(beneficiary)
            .param(amountPaise)
            .param(status.name())
            .param(failureReason)
            .param(ts(createdAt))
            .param(ts(settledAt))
            .param(ts(asyncDueAt))
            .param(asyncOutcome == null ? null : asyncOutcome.name())
            .update();
        return new ProviderPayout(id, key, beneficiary, amountPaise, status, failureReason, createdAt, settledAt);
    }

    /** PROCESSING -> CANCELLED. Returns false if the row was not PROCESSING any more. */
    public boolean cancelIfProcessing(String key) {
        return jdbc.sql("UPDATE provider.payouts SET status = 'CANCELLED' WHERE idempotency_key = ? AND status = 'PROCESSING'")
            .param(key)
            .update() == 1;
    }

    /** ASYNC payouts whose time has come. */
    public List<UUID> findDueAsync(Instant now) {
        return jdbc.sql("""
                SELECT id FROM provider.payouts
                WHERE status = 'PROCESSING' AND async_due_at <= ?
                ORDER BY async_due_at, id
                """)
            .param(ts(now))
            .query(UUID.class)
            .list();
    }

    /**
     * Settle one ASYNC payout to its pre-chosen outcome. Conditional on PROCESSING, so a
     * cancel that got there first wins. Returns false if nothing changed.
     */
    public boolean settleAsync(UUID id, Instant now) {
        return jdbc.sql("""
                UPDATE provider.payouts
                SET status = async_outcome,
                    failure_reason = CASE WHEN async_outcome = 'FAILED' THEN 'INVALID_ACCOUNT' END,
                    settled_at = ?
                WHERE id = ? AND status = 'PROCESSING'
                """)
            .param(ts(now))
            .param(id)
            .update() == 1;
    }

    /** SUCCESS payouts with from <= settled_at < to. */
    public List<ProviderPayout> findSettled(Instant from, Instant to) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM provider.payouts
                WHERE status = 'SUCCESS' AND settled_at >= ? AND settled_at < ?
                ORDER BY settled_at, id
                """)
            .param(ts(from))
            .param(ts(to))
            .query(PayoutRepository::map)
            .list();
    }

    public void truncate() {
        jdbc.sql("TRUNCATE provider.payouts").update();
    }

    private static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime ts(Instant instant) {
        // pgjdbc can't bind java.time.Instant; OffsetDateTime maps to TIMESTAMPTZ.
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static ProviderPayout map(ResultSet rs, int row) throws SQLException {
        Long amount = rs.getObject("amount_paise", Long.class);   // NULL for a tombstone
        return new ProviderPayout(
            rs.getObject("id", UUID.class),
            rs.getString("idempotency_key"),
            rs.getString("beneficiary"),
            amount,
            Status.valueOf(rs.getString("status")),
            rs.getString("failure_reason"),
            instant(rs, "created_at"),
            instant(rs, "settled_at"));
    }
}
