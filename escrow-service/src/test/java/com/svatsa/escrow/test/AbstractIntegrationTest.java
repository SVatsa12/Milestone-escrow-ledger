package com.svatsa.escrow.test;

import com.svatsa.escrow.recon.InvariantChecker;
import com.svatsa.escrow.recon.Violation;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for all integration tests.
 * 
 * Sets up shared testcontainers (Postgres, Kafka, Redis) via Spring's @ServiceConnection
 * and provides utilities for verifying ledger invariants.
 * 
 * Test profile uses H2 in-memory DB for unit tests; Testcontainers are used when needed
 * in specific test classes that override the profile.
 */
@SpringBootTest(properties = "spring.profiles.active=test")
public class AbstractIntegrationTest {

    @Autowired
    protected JdbcClient jdbcClient;

    @Autowired
    protected InvariantChecker invariantChecker;

    @Autowired
    protected Clock clock;

    /**
     * After each test, verify that all ledger invariants still hold.
     * 
     * This catches bugs early: if a test leaves the ledger in an inconsistent state,
     * we fail immediately instead of letting the corruption propagate.
     */
    @AfterEach
    void verifyInvariants() {
        List<Violation> violations = invariantChecker.check();
        assertThat(violations)
            .as("Ledger invariants must hold after every test")
            .isEmpty();
    }

    /**
     * Delete all ledger data (for test isolation).
     * 
     * Called between tests if needed. Must be in a transaction.
     */
    @Transactional
    protected void clearLedger() {
        jdbcClient.sql("TRUNCATE TABLE postings CASCADE").update();
        jdbcClient.sql("TRUNCATE TABLE journal_entries CASCADE").update();
        jdbcClient.sql("TRUNCATE TABLE idempotency_keys CASCADE").update();
        jdbcClient.sql("UPDATE accounts SET balance = 0").update();
    }
}
