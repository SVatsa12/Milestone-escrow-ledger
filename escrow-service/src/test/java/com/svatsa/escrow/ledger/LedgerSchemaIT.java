package com.svatsa.escrow.ledger;

import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests proving the ledger schema DB guards work correctly.
 * 
 * These tests operate directly with SQL through JdbcClient to verify that
 * the database constraints (triggers, CHECK, UNIQUE) enforce invariants.
 * 
 * NOTE: These tests require Postgres with Flyway migrations. For development,
 * run with `docker compose up -d` then override the test database URL.
 * Currently disabled for local H2 testing; enable in CI where Postgres is available.
 */
@Disabled("Requires Postgres; enable in CI or with docker-compose")
class LedgerSchemaIT extends AbstractIntegrationTest {

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void unbalancedEntryFailsAtCommit() {
        // Arrange: create two accounts
        Account from = accountRepository.create("TEST:FROM", AccountType.CLIENT_WALLET, null, false);
        Account to = accountRepository.create("TEST:TO", AccountType.FREELANCER_WALLET, null, false);
        
        // Fund them
        jdbcClient.sql("UPDATE accounts SET balance = 100 WHERE id = ?").param(from.id()).update();
        jdbcClient.sql("UPDATE accounts SET balance = 100 WHERE id = ?").param(to.id()).update();

        // Act & Assert: try to insert an unbalanced entry (sum = +1 instead of 0)
        // The deferred constraint trigger should fire at commit
        assertThatThrownBy(() -> 
            transactionTemplate.executeWithoutResult(status -> {
                jdbcClient.sql("""
                    INSERT INTO journal_entries (type, idempotency_key)
                    VALUES (?, ?)
                    """)
                    .param("TEST")
                    .param("unbalanced-key-1")
                    .update();

                long entryId = jdbcClient.sql("SELECT LASTVAL()").query(Long.class).single();

                // Insert unbalanced postings: +100 to "to", nothing from "from"
                // Sum = +100, not 0
                jdbcClient.sql("""
                    INSERT INTO postings (entry_id, account_id, amount)
                    VALUES (?, ?, ?)
                    """)
                    .param(entryId)
                    .param(to.id())
                    .param(100)
                    .update();
            })
        )
        .hasMessageContaining("does not balance");
    }

    @Test
    void entryWithSinglePostingFailsAtCommit() {
        // Arrange
        Account acc = accountRepository.create("TEST:SINGLE", AccountType.CLIENT_WALLET, null, false);
        
        // Act & Assert: try to insert an entry with only 1 posting
        assertThatThrownBy(() ->
            transactionTemplate.executeWithoutResult(status -> {
                jdbcClient.sql("""
                    INSERT INTO journal_entries (type, idempotency_key)
                    VALUES (?, ?)
                    """)
                    .param("TEST")
                    .param("single-posting-key-1")
                    .update();

                long entryId = jdbcClient.sql("SELECT LASTVAL()").query(Long.class).single();

                // Insert only 1 posting
                jdbcClient.sql("""
                    INSERT INTO postings (entry_id, account_id, amount)
                    VALUES (?, ?, ?)
                    """)
                    .param(entryId)
                    .param(acc.id())
                    .param(100)
                    .update();
            })
        )
        .hasMessageContaining("has only");
    }

    @Test
    void balancedEntryCommits() {
        // Arrange
        Account from = accountRepository.create("TEST:BALANCED_FROM", AccountType.CLIENT_WALLET, null, false);
        Account to = accountRepository.create("TEST:BALANCED_TO", AccountType.FREELANCER_WALLET, null, false);
        
        jdbcClient.sql("UPDATE accounts SET balance = 100 WHERE id = ?").param(from.id()).update();
        jdbcClient.sql("UPDATE accounts SET balance = 100 WHERE id = ?").param(to.id()).update();

        // Act: insert a balanced entry
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                INSERT INTO journal_entries (type, idempotency_key)
                VALUES (?, ?)
                """)
                .param("TEST")
                .param("balanced-key-1")
                .update();

            long entryId = jdbcClient.sql("SELECT LASTVAL()").query(Long.class).single();

            // Insert balanced postings: -50 from "from", +50 to "to" (sum = 0)
            jdbcClient.sql("""
                INSERT INTO postings (entry_id, account_id, amount)
                VALUES (?, ?, ?), (?, ?, ?)
                """)
                .param(entryId).param(from.id()).param(-50)
                .param(entryId).param(to.id()).param(50)
                .update();
        });

        // Assert: entry was committed
        Long entryCount = jdbcClient.sql("SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?")
            .param("balanced-key-1")
            .query(Long.class)
            .single();
        assertThat(entryCount).isEqualTo(1);
    }

    @Test
    void updateOnJournalEntriesFails() {
        // Arrange: create a journal entry
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                INSERT INTO journal_entries (type, idempotency_key)
                VALUES (?, ?)
                """)
                .param("TEST")
                .param("immutable-key-1")
                .update();
        });

        // Act & Assert: try to UPDATE it
        long entryId = jdbcClient.sql("SELECT id FROM journal_entries WHERE idempotency_key = ?")
            .param("immutable-key-1")
            .query(Long.class)
            .single();

        assertThatThrownBy(() ->
            jdbcClient.sql("UPDATE journal_entries SET type = ? WHERE id = ?")
                .param("MODIFIED")
                .param(entryId)
                .update()
        )
        .hasMessageContaining("immutable");
    }

    @Test
    void deleteOnJournalEntriesFails() {
        // Arrange: create a journal entry
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                INSERT INTO journal_entries (type, idempotency_key)
                VALUES (?, ?)
                """)
                .param("TEST")
                .param("delete-key-1")
                .update();
        });

        long entryId = jdbcClient.sql("SELECT id FROM journal_entries WHERE idempotency_key = ?")
            .param("delete-key-1")
            .query(Long.class)
            .single();

        // Act & Assert: try to DELETE it
        assertThatThrownBy(() ->
            jdbcClient.sql("DELETE FROM journal_entries WHERE id = ?")
                .param(entryId)
                .update()
        )
        .hasMessageContaining("immutable");
    }

    @Test
    void nonNegativeAccountCannotGoNegative() {
        // Arrange
        Account acc = accountRepository.create("TEST:NON_NEGATIVE", AccountType.CLIENT_WALLET, null, false);
        
        // Act & Assert: try to set balance negative
        assertThatThrownBy(() ->
            jdbcClient.sql("UPDATE accounts SET balance = ? WHERE id = ?")
                .param(-1)
                .param(acc.id())
                .update()
        )
        .hasMessageContaining("CHECK");
    }

    @Test
    void negativeAccountCanGoNegative() {
        // Arrange: create an account that allows negative (like external bank)
        Account acc = accountRepository.create("TEST:NEGATIVE", AccountType.EXTERNAL_BANK, null, true);
        
        // Act: set it negative (should succeed)
        jdbcClient.sql("UPDATE accounts SET balance = ? WHERE id = ?")
            .param(-1000)
            .param(acc.id())
            .update();

        // Assert
        Long balance = jdbcClient.sql("SELECT balance FROM accounts WHERE id = ?")
            .param(acc.id())
            .query(Long.class)
            .single();
        assertThat(balance).isEqualTo(-1000);
    }
}
