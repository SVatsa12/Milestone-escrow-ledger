package com.svatsa.escrow.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Leader election for the outbox relay: only the instance holding a Postgres SESSION advisory lock
 * on {@link #LOCK_KEY} publishes. Two relays publishing at once could reorder an aggregate's events
 * (chaos/FINDINGS.md #51): relay A's claim locks event 1, relay B's SKIP LOCKED claim skips it and
 * sends event 2 first.
 *
 * <ul>
 *   <li>The leader keeps one dedicated connection for as long as it leads; the lock lives exactly as
 *       long as that database session.</li>
 *   <li>A follower borrows a connection, tries {@code pg_try_advisory_lock}, and gives the connection
 *       back to the pool if it lost. It tries again on the next tick.</li>
 *   <li>Failover: when the leader dies, its connection closes, Postgres ends the session and
 *       releases the lock, and the next follower to tick takes over. A leader whose connection broke
 *       (network, pg_terminate_backend) notices on its next tick, evicts the connection and becomes a
 *       follower.</li>
 * </ul>
 *
 * Leadership is checked before every batch, not during one: a leader that loses its session in the
 * middle of a batch finishes sending that batch (at most {@code batch-size} rows, bounded by the ack
 * timeout) while the new leader may already be sending. That overlap can duplicate (consumers dedupe
 * by eventId) and, within that one batch window, reorder. There is no fencing token.
 */
public class OutboxRelayLeadership implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayLeadership.class);

    /** Fixed advisory-lock key for "the outbox relay" ("escrowOB" in ASCII). Every instance uses it. */
    public static final long LOCK_KEY = 0x657363726f774f42L;

    private final DataSource dataSource;
    private final String instance;
    private Connection connection;     // non-null only while we hold the lock
    private volatile boolean leader;

    public OutboxRelayLeadership(DataSource dataSource, String instance) {
        this.dataSource = dataSource;
        this.instance = instance;
    }

    public boolean isLeader() {
        return leader;
    }

    /**
     * Called before each batch: true if this instance leads now (still, or newly). Never throws: a
     * database error just means "not the leader this tick".
     */
    public synchronized boolean ensureLeadership() {
        if (connection != null) {
            if (stillHeld()) {
                return true;
            }
            log.warn("outbox.relay.leadership-lost instance={} reason=leader-connection-broken", instance);
            discard();
        }
        tryAcquire();
        return leader;
    }

    private boolean stillHeld() {
        try {
            return connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private void tryAcquire() {
        Connection candidate = null;
        try {
            candidate = dataSource.getConnection();
            candidate.setAutoCommit(true);
            boolean acquired;
            try (PreparedStatement ps = candidate.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, LOCK_KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    acquired = rs.next() && rs.getBoolean(1);
                }
            }
            if (acquired) {
                connection = candidate;
                leader = true;
                log.info("outbox.relay.leadership-acquired instance={}", instance);
            } else {
                candidate.close(); // a failed try holds no lock: safe to hand back to the pool
                leader = false;
            }
        } catch (SQLException e) {
            leader = false;
            if (candidate != null) {
                evict(candidate);
            }
            log.debug("outbox.relay.leadership-attempt-failed instance={} error={}", instance, e.getMessage());
        }
    }

    /** The leader connection is broken: its session (and lock) is gone or going. Never pool it again. */
    private void discard() {
        leader = false;
        evict(connection);
        connection = null;
    }

    /** Close the physical connection and drop it from the pool (a pooled close would keep the session). */
    private void evict(Connection c) {
        try {
            if (dataSource.isWrapperFor(com.zaxxer.hikari.HikariDataSource.class)) {
                dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).evictConnection(c);
                return;
            }
        } catch (SQLException | RuntimeException ignored) {
            // fall through to a plain close
        }
        try {
            c.close();
        } catch (SQLException ignored) {
            // already unusable
        }
    }

    /**
     * Step down (shutdown, tests). Closing a POOLED connection does not end its session, so unlock
     * explicitly before handing it back; otherwise the lock would outlive this instance.
     */
    @Override
    public synchronized void close() {
        if (connection == null) {
            leader = false;
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, LOCK_KEY);
            ps.execute();
            connection.close();
            log.info("outbox.relay.leadership-released instance={}", instance);
        } catch (SQLException e) {
            evict(connection); // can't unlock: drop the session so Postgres releases the lock
        } finally {
            connection = null;
            leader = false;
        }
    }
}
