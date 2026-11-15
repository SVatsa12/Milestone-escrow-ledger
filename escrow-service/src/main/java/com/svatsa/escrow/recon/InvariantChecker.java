package com.svatsa.escrow.recon;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import com.svatsa.escrow.common.SqlTime;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies ledger invariants through SQL queries.
 * 
 * Invariants (I1-I10) are properties that must always hold if the system is correct.
 * Each is checked via a SQL query; violations indicate a bug or corruption.
 * 
 * I1: Every journal entry sums to 0 and has >= 2 postings
 *     (enforced by DB constraint trigger, but we verify it)
 * 
 * I2: Sum of all postings across all entries = 0
 *     (global ledger balance, money is not created or destroyed)
 * 
 * I3: For each account, balance = SUM(amount) of postings to that account
 *     (balance is denormalized and must be consistent)
 * 
 * I4: No account with allow_negative=false has balance < 0
 *     (enforced by CHECK constraint, but we verify it)
 * 
 * I5: No duplicate idempotency_key in journal_entries
 *     (enforced by UNIQUE index, but we verify it)
 * 
 * I6: Escrow conservation: for each project, ESCROW account balance = sum of milestones
 *     in FUNDED, SUBMITTED, or APPROVED states; DISPUTE_HOLD balance = sum of milestones
 *     in DISPUTED state.
 *
 * I7: PAYOUT_IN_FLIGHT account balance = SUM(amount_paise) of payouts in
 *     (RESERVED, PROCESSING, UNKNOWN). Terminal payouts (CONFIRMED/FAILED/CANCELLED)
 *     have no funds in PAYOUT_IN_FLIGHT.
 *
 * I8: Payout saga consistency: CONFIRMED payouts have exactly one PAYOUT_CONFIRM entry
 *     and zero reverse entries; FAILED/CANCELLED payouts have exactly one reverse entry
 *     (PAYOUT_REVERSE or PAYOUT_CANCEL) and zero confirm entries.
 *
 * I9: Outbox staleness (warning): no unpublished outbox rows older than 5 minutes.
 *     Indicates relay job may be stuck or Kafka unavailable.
 *
 * I10: Business idempotency keys (invariant 8): every journal entry of type FUND, RELEASE,
 *     DISPUTE_FREEZE, DISPUTE_RESOLVE, REFUND, DEPOSIT or PAYOUT_* carries exactly the key the
 *     AGENTS.md money-flows table gives for its reference (see LedgerKeys). A DEPOSIT's key is
 *     deposit:rzp:{the payment id recorded on the deposit it references}. An unknown PAYOUT_*
 *     type is a violation too.
 */
@Service
public class InvariantChecker {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public InvariantChecker(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    /**
     * Check all invariants (I1-I10) and return a list of violations (empty if all OK).
     */
    public List<Violation> check() {
        // No try/catch: a check that cannot run must fail loudly. This used to catch every
        // exception and return "no violations" (so it passed on H2, where the tables didn't
        // exist), which meant the @AfterEach invariant assertion proved nothing.
        List<Violation> violations = new ArrayList<>();
        violations.addAll(checkI1());
        violations.addAll(checkI2());
        violations.addAll(checkI3());
        violations.addAll(checkI4());
        violations.addAll(checkI5());
        violations.addAll(checkI6());
        violations.addAll(checkI7());
        violations.addAll(checkI8());
        violations.addAll(checkI9());
        violations.addAll(checkI10());
        return violations;
    }

    /**
     * I1: Every journal entry sums to 0 and has >= 2 postings.
     */
    private List<Violation> checkI1() {
        List<Violation> violations = new ArrayList<>();

        // Find entries where sum <> 0
        List<Long> unbalancedIds = jdbcClient.sql("""
            SELECT entry_id
            FROM (
                SELECT entry_id, SUM(amount) as total
                FROM postings
                GROUP BY entry_id
            ) t
            WHERE total <> 0
            """)
            .query(Long.class)
            .list();

        for (Long id : unbalancedIds) {
            violations.add(new Violation("I1",
                "Journal entry " + id + " does not sum to 0"));
        }

        // Find entries with fewer than 2 postings
        List<Long> underpostedIds = jdbcClient.sql("""
            SELECT entry_id
            FROM (
                SELECT entry_id, COUNT(*) as cnt
                FROM postings
                GROUP BY entry_id
            ) t
            WHERE cnt < 2
            """)
            .query(Long.class)
            .list();

        for (Long id : underpostedIds) {
            violations.add(new Violation("I1",
                "Journal entry " + id + " has fewer than 2 postings"));
        }

        // Entries with NO postings never show up when grouping postings; look for them directly.
        List<Long> emptyIds = jdbcClient.sql("""
            SELECT je.id FROM journal_entries je
            WHERE NOT EXISTS (SELECT 1 FROM postings p WHERE p.entry_id = je.id)
            """)
            .query(Long.class)
            .list();
        for (Long id : emptyIds) {
            violations.add(new Violation("I1", "Journal entry " + id + " has no postings"));
        }

        return violations;
    }

    /**
     * I2: Sum of all postings across all entries = 0.
     */
    private List<Violation> checkI2() {
        Long totalSum = jdbcClient.sql("SELECT COALESCE(SUM(amount), 0) FROM postings")
            .query(Long.class)
            .single();

        if (totalSum != 0) {
            return List.of(new Violation("I2",
                "Total postings sum to " + totalSum + ", expected 0 (money created/destroyed)"));
        }

        return List.of();
    }

    /**
     * I3: For each account, balance = SUM(postings) to that account.
     */
    private List<Violation> checkI3() {
        List<Violation> violations = new ArrayList<>();

        List<String> mismatches = jdbcClient.sql("""
            SELECT a.id || ':' || a.code || ' has balance ' || a.balance || 
                   ' but postings sum to ' || COALESCE(p.total, 0)
            FROM accounts a
            LEFT JOIN (
                SELECT account_id, SUM(amount) as total
                FROM postings
                GROUP BY account_id
            ) p ON a.id = p.account_id
            WHERE a.balance <> COALESCE(p.total, 0)
            """)
            .query(String.class)
            .list();

        for (String mismatch : mismatches) {
            violations.add(new Violation("I3", "Balance mismatch: " + mismatch));
        }

        return violations;
    }

    /**
     * I4: No account with allow_negative=false has balance < 0.
     */
    private List<Violation> checkI4() {
        List<Violation> violations = new ArrayList<>();

        List<String> negatives = jdbcClient.sql("""
            SELECT id || ':' || code || ' (allow_negative=false) has balance ' || balance
            FROM accounts
            WHERE allow_negative = false AND balance < 0
            """)
            .query(String.class)
            .list();

        for (String negative : negatives) {
            violations.add(new Violation("I4",
                "Non-negative account has negative balance: " + negative));
        }

        return violations;
    }

    /**
     * I5: No duplicate idempotency_key in journal_entries.
     */
    private List<Violation> checkI5() {
        List<Violation> violations = new ArrayList<>();

        // Count duplicates in journal_entries
        Integer duplicateCount = jdbcClient.sql("""
            SELECT COUNT(*) - COUNT(DISTINCT idempotency_key)
            FROM journal_entries
            """)
            .query(Integer.class)
            .single();

        if (duplicateCount != null && duplicateCount > 0) {
            violations.add(new Violation("I5",
                "Found " + duplicateCount + " duplicate idempotency keys in journal_entries"));
        }

        return violations;
    }

    /**
     * I6: Escrow conservation.
     * For each project:
     * - ESCROW account balance = sum of milestone amounts in FUNDED, SUBMITTED, APPROVED
     * - DISPUTE_HOLD account balance = sum of milestone amounts in DISPUTED
     */
    private List<Violation> checkI6() {
        List<Violation> violations = new ArrayList<>();

        // Check ESCROW account balances
        List<String> escrowMismatches = jdbcClient.sql("""
            SELECT 
                p.id || ': ESCROW account ' || a.id || 
                ' has balance ' || a.balance || 
                ' but in-escrow milestones sum to ' || COALESCE(m.total, 0)
            FROM projects p
            JOIN accounts a ON p.escrow_account_id = a.id
            LEFT JOIN (
                SELECT project_id, SUM(amount_paise) as total
                FROM milestones
                WHERE state IN ('FUNDED', 'SUBMITTED', 'APPROVED')
                GROUP BY project_id
            ) m ON p.id = m.project_id
            WHERE a.balance <> COALESCE(m.total, 0)
            """)
            .query(String.class)
            .list();

        for (String mismatch : escrowMismatches) {
            violations.add(new Violation("I6", "ESCROW balance mismatch: " + mismatch));
        }

        // Check DISPUTE_HOLD account balances
        List<String> disputeMismatches = jdbcClient.sql("""
            SELECT 
                p.id || ': DISPUTE_HOLD account ' || a.id || 
                ' has balance ' || a.balance || 
                ' but disputed milestones sum to ' || COALESCE(m.total, 0)
            FROM projects p
            JOIN accounts a ON p.dispute_hold_account_id = a.id
            LEFT JOIN (
                SELECT project_id, SUM(amount_paise) as total
                FROM milestones
                WHERE state = 'DISPUTED'
                GROUP BY project_id
            ) m ON p.id = m.project_id
            WHERE a.balance <> COALESCE(m.total, 0)
            """)
            .query(String.class)
            .list();

        for (String mismatch : disputeMismatches) {
            violations.add(new Violation("I6", "DISPUTE_HOLD balance mismatch: " + mismatch));
        }

        return violations;
    }

    /**
     * I7: PAYOUT_IN_FLIGHT account balance = SUM(amount_paise) of payouts in 
     * (RESERVED, PROCESSING, UNKNOWN).
     */
    private List<Violation> checkI7() {
        List<Violation> violations = new ArrayList<>();

        Long accountBalance = jdbcClient.sql(
            "SELECT COALESCE(balance, 0) FROM accounts WHERE code = 'PAYOUT_IN_FLIGHT'")
            .query(Long.class)
            .optional()
            .orElse(0L);

        Long payoutSum = jdbcClient.sql("""
            SELECT COALESCE(SUM(amount_paise), 0)
            FROM payouts
            WHERE state IN ('RESERVED', 'PROCESSING', 'UNKNOWN')
            """)
            .query(Long.class)
            .single();

        if (accountBalance != payoutSum) {
            violations.add(new Violation("I7",
                "PAYOUT_IN_FLIGHT account balance (" + accountBalance + 
                ") != pending payouts sum (" + payoutSum + ")"));
        }

        return violations;
    }

    /**
     * I8: Every CONFIRMED payout has exactly one confirm entry and no reverse entry;
     * every FAILED/CANCELLED payout has exactly one reverse entry and no confirm entry.
     */
    private List<Violation> checkI8() {
        List<Violation> violations = new ArrayList<>();

        // Check CONFIRMED payouts
        List<String> confirmedMismatches = jdbcClient.sql("""
            SELECT p.id
            FROM payouts p
            WHERE p.state = 'CONFIRMED'
            AND (
                (SELECT COUNT(*) FROM journal_entries 
                 WHERE reference_type = 'PAYOUT' AND reference_id = p.id::TEXT 
                 AND type = 'PAYOUT_CONFIRM') <> 1
                OR
                (SELECT COUNT(*) FROM journal_entries 
                 WHERE reference_type = 'PAYOUT' AND reference_id = p.id::TEXT 
                 AND type IN ('PAYOUT_REVERSE', 'PAYOUT_CANCEL')) > 0
            )
            """)
            .query(String.class)
            .list();

        for (String payoutId : confirmedMismatches) {
            violations.add(new Violation("I8",
                "CONFIRMED payout " + payoutId + " missing or duplicate confirm/reverse entry"));
        }

        // Check FAILED/CANCELLED payouts
        List<String> failedMismatches = jdbcClient.sql("""
            SELECT p.id
            FROM payouts p
            WHERE p.state IN ('FAILED', 'CANCELLED')
            AND (
                (SELECT COUNT(*) FROM journal_entries 
                 WHERE reference_type = 'PAYOUT' AND reference_id = p.id::TEXT 
                 AND type IN ('PAYOUT_REVERSE', 'PAYOUT_CANCEL')) <> 1
                OR
                (SELECT COUNT(*) FROM journal_entries 
                 WHERE reference_type = 'PAYOUT' AND reference_id = p.id::TEXT 
                 AND type = 'PAYOUT_CONFIRM') > 0
            )
            """)
            .query(String.class)
            .list();

        for (String payoutId : failedMismatches) {
            violations.add(new Violation("I8",
                "FAILED/CANCELLED payout " + payoutId + " missing or duplicate reverse/confirm entry"));
        }

        return violations;
    }

    /**
     * I9: Every outbox row older than 5 minutes is published.
     * (Warning level, not a money violation, but indicates relay issues)
     * "Older" is measured on the injected Clock, the same clock OutboxWriter stamps created_at with
     * (it used to compare DB now() with a created_at taken from system time).
     */
    private List<Violation> checkI9() {
        List<Violation> violations = new ArrayList<>();

        Long staleCount = jdbcClient.sql("""
            SELECT COUNT(*)
            FROM outbox
            WHERE published_at IS NULL
            AND created_at < ?
            """)
            .param(SqlTime.ts(clock.instant().minus(Duration.ofMinutes(5))))
            .query(Long.class)
            .single();

        if (staleCount != null && staleCount > 0) {
            violations.add(new Violation("I9_WARN",
                "Found " + staleCount + " unpublished outbox rows older than 5 minutes " +
                "(relay may be stuck)"));
        }

        return violations;
    }

    /**
     * I10: every business-flow entry has the idempotency key its type and reference dictate.
     */
    private List<Violation> checkI10() {
        List<String> wrong = jdbcClient.sql("""
            SELECT 'entry ' || je.id || ' (' || je.type || ' ' || COALESCE(je.reference_type, '-') || ':'
                   || COALESCE(je.reference_id, '-') || ') has key ' || je.idempotency_key
                   || ', expected ' || COALESCE(x.expected, '(no pattern for this type/reference)')
            FROM journal_entries je
            LEFT JOIN deposits d
                   ON je.type = 'DEPOSIT' AND je.reference_type = 'DEPOSIT' AND d.id::text = je.reference_id
            CROSS JOIN LATERAL (
                SELECT CASE
                    WHEN je.type = 'FUND' AND je.reference_type = 'PROJECT'
                        THEN 'fund:project:' || je.reference_id
                    WHEN je.type = 'RELEASE' AND je.reference_type = 'MILESTONE'
                        THEN 'release:milestone:' || je.reference_id
                    WHEN je.type = 'DISPUTE_FREEZE' AND je.reference_type = 'MILESTONE'
                        THEN 'dispute-freeze:milestone:' || je.reference_id
                    WHEN je.type = 'DISPUTE_RESOLVE' AND je.reference_type = 'DISPUTE'
                        THEN 'dispute-resolve:' || je.reference_id
                    WHEN je.type = 'REFUND' AND je.reference_type = 'MILESTONE'
                        THEN 'refund:milestone:' || je.reference_id
                    WHEN je.type = 'DEPOSIT'
                        THEN 'deposit:rzp:' || d.razorpay_payment_id
                    WHEN je.type = 'PAYOUT_RESERVE' AND je.reference_type = 'PAYOUT'
                        THEN 'payout:reserve:' || je.reference_id
                    WHEN je.type = 'PAYOUT_CONFIRM' AND je.reference_type = 'PAYOUT'
                        THEN 'payout:confirm:' || je.reference_id
                    WHEN je.type IN ('PAYOUT_REVERSE', 'PAYOUT_CANCEL') AND je.reference_type = 'PAYOUT'
                        THEN 'payout:reverse:' || je.reference_id
                END AS expected
            ) x
            WHERE (je.type IN ('FUND', 'RELEASE', 'DISPUTE_FREEZE', 'DISPUTE_RESOLVE', 'REFUND', 'DEPOSIT')
                   OR left(je.type, 7) = 'PAYOUT_')
              AND je.idempotency_key IS DISTINCT FROM x.expected
            ORDER BY je.id
            """)
            .query(String.class)
            .list();

        List<Violation> violations = new ArrayList<>();
        for (String w : wrong) {
            violations.add(new Violation("I10", "Business idempotency key mismatch: " + w));
        }
        return violations;
    }
}
