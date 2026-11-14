package com.svatsa.escrow.ledger;

import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2: proves the database guards on their own, in raw SQL through JdbcClient (no LedgerService).
 *
 * Every scenario leaves the shared database consistent, because the @AfterEach invariant check
 * covers the whole ledger:
 * - scenarios that must fail at commit roll back by themselves;
 * - "allowed but not a real posting" writes are asserted inside a transaction that is then
 *   rolled back;
 * - the one entry that commits also moves the cached balances, exactly as LedgerService does.
 * Codes and idempotency keys are unique per run.
 */
class LedgerSchemaIT extends AbstractIntegrationTest {

    @Autowired private TransactionTemplate tx;
    @Autowired private AccountRepository accountRepository;

    private final String run = UUID.randomUUID().toString().substring(0, 8);

    @Test
    void unbalancedEntryFailsAtCommit() {
        Account to = account("UNBAL_TO", false);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            long entryId = insertEntry("unbalanced");
            insertPosting(entryId, to.id(), 100);   // sum = +100
        }))
            .hasMessageContaining("does not balance");
    }

    @Test
    void entryWithSinglePostingFailsAtCommit() {
        // A single posting can never sum to 0 (postings.amount <> 0 is enforced), so the trigger
        // reports the imbalance first; what matters is that the commit is refused.
        Account acc = account("SINGLE", false);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            long entryId = insertEntry("single");
            insertPosting(entryId, acc.id(), 100);
        }))
            .hasMessageContaining("does not balance");
    }

    @Test
    void entryWithNoPostingsFailsAtCommit() {
        // Used to commit: the balancing trigger fired only on postings inserts (fixed in V9).
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> insertEntry("empty")))
            .hasMessageContaining("has only 0 posting");
    }

    @Test
    void balancedEntryCommits() {
        String key = key("balanced");
        Account from = account("BAL_FROM", true);   // may go negative, like EXTERNAL_BANK
        Account to = account("BAL_TO", false);

        tx.executeWithoutResult(s -> {
            long entryId = insertEntry(key);
            insertPosting(entryId, from.id(), -50);
            insertPosting(entryId, to.id(), 50);
            // Keep the cached projection in step with the postings, as LedgerService.post does.
            moveBalance(from.id(), -50);
            moveBalance(to.id(), 50);
        });

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?")
            .param(key).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void updateOnJournalEntriesAndPostingsFails() {
        long entryId = committedBalancedEntry("upd");

        assertThatThrownBy(() -> jdbcClient.sql("UPDATE journal_entries SET type = 'MODIFIED' WHERE id = ?")
            .param(entryId).update())
            .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE postings SET amount = 5 WHERE entry_id = ?")
            .param(entryId).update())
            .hasMessageContaining("immutable");
    }

    @Test
    void deleteOnJournalEntriesAndPostingsFails() {
        long entryId = committedBalancedEntry("del");

        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM postings WHERE entry_id = ?")
            .param(entryId).update())
            .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM journal_entries WHERE id = ?")
            .param(entryId).update())
            .hasMessageContaining("immutable");
    }

    @Test
    void nonNegativeAccountCannotGoNegative() {
        Account acc = account("NON_NEG", false);
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE accounts SET balance = -1 WHERE id = ?")
            .param(acc.id()).update())
            .hasMessageContaining("balance_non_negative");
    }

    @Test
    void allowNegativeAccountCanGoNegative() {
        Account acc = account("NEG_OK", true);
        // Assert inside a transaction and roll it back: a balance with no postings behind it
        // would (correctly) fail the I3 check after the test.
        tx.executeWithoutResult(s -> {
            jdbcClient.sql("UPDATE accounts SET balance = -1000 WHERE id = ?").param(acc.id()).update();
            assertThat(jdbcClient.sql("SELECT balance FROM accounts WHERE id = ?")
                .param(acc.id()).query(Long.class).single()).isEqualTo(-1000);
            s.setRollbackOnly();
        });
    }

    // --- raw SQL helpers ---------------------------------------------------------------------

    private Account account(String name, boolean allowNegative) {
        return accountRepository.create("SCHEMA:" + name + ":" + run,
            allowNegative ? AccountType.EXTERNAL_BANK : AccountType.CLIENT_WALLET, null, allowNegative);
    }

    private String key(String name) {
        return "schema-" + name + "-" + run;
    }

    private long insertEntry(String name) {
        return jdbcClient.sql("INSERT INTO journal_entries (type, idempotency_key) VALUES ('TEST', ?) RETURNING id")
            .param(name.startsWith("schema-") ? name : key(name))
            .query(Long.class).single();
    }

    private void insertPosting(long entryId, long accountId, long amount) {
        jdbcClient.sql("INSERT INTO postings (entry_id, account_id, amount) VALUES (?, ?, ?)")
            .param(entryId).param(accountId).param(amount).update();
    }

    private void moveBalance(long accountId, long delta) {
        jdbcClient.sql("UPDATE accounts SET balance = balance + ? WHERE id = ?").param(delta).param(accountId).update();
    }

    private long committedBalancedEntry(String name) {
        Account from = account(name + "_FROM", true);
        Account to = account(name + "_TO", false);
        return tx.execute(s -> {
            long entryId = insertEntry(name);
            insertPosting(entryId, from.id(), -10);
            insertPosting(entryId, to.id(), 10);
            moveBalance(from.id(), -10);
            moveBalance(to.id(), 10);
            return entryId;
        });
    }
}
