package com.svatsa.escrow.ledger;

import com.svatsa.escrow.common.ValidationException;
import com.svatsa.escrow.outbox.OutboxWriter;
import com.svatsa.escrow.outbox.events.Events;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ledger posting engine.
 * 
 * This is the ONLY service that writes to the ledger. All money movements go through post().
 * 
 * post() is @Transactional(propagation = MANDATORY): it must run inside an outer transaction
 * (from the service method that called it). This ensures that a posting and its triggering
 * state change (e.g., funding a milestone) either both succeed or both roll back.
 * 
 * Invariants enforced:
 * - I1 (balanced entries): DB constraint trigger
 * - I2 (sum = 0): enforced by requiring balanced legs
 * - I3 (balance = sum): account balances are updated in same transaction as postings
 * - I4 (no negative): checked before updating
 * - I5 (no duplicate keys): UNIQUE index, plus we check and replay
 */
@Service
public class LedgerService {

    private final JdbcClient jdbcClient;
    private final AccountRepository accountRepository;
    private final OutboxWriter outboxWriter;

    public LedgerService(
        JdbcClient jdbcClient,
        AccountRepository accountRepository,
        OutboxWriter outboxWriter
    ) {
        this.jdbcClient = jdbcClient;
        this.accountRepository = accountRepository;
        this.outboxWriter = outboxWriter;
    }

    /**
     * Post a balanced journal entry (transaction-mandatory).
     * 
     * This method MUST be called from within a @Transactional service method.
     * Violating this invariant (calling without a transaction) will fail loudly.
     * 
     * Steps:
     * 1. Validate legs: >= 2, sum to 0, no duplicate accountIds, idempotency key not blank
     * 2. Check if this entry already exists (replay detection)
     * 3. Lock all involved accounts in ascending ID order (deadlock prevention)
     * 4. Check that all accounts exist and new balances won't violate constraints
     * 5. Insert the journal entry (unique key is a race detector)
     * 6. Batch-insert postings and update account balances atomically
     * 
     * @param request the posting request with legs and metadata
     * @return PostingResult with entryId and replayed flag
     * @throws ValidationException if the request is invalid
     * @throws InsufficientFundsException if an account would go negative
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostingResult post(PostingRequest request) {
        // 1. Validate
        validateRequest(request);

        // 2. Check for replay
        Long existingEntryId = jdbcClient.sql(
                "SELECT id FROM journal_entries WHERE idempotency_key = ?")
            .param(request.idempotencyKey())
            .query(Long.class)
            .optional()
            .orElse(null);

        if (existingEntryId != null) {
            return new PostingResult(existingEntryId, true);
        }

        // 3. Lock accounts in ascending ID order (prevents deadlock in opposite transfers)
        List<Long> accountIds = request.legs().stream()
            .map(Leg::accountId)
            .distinct()
            .sorted()
            .toList();

        List<Account> accounts = jdbcClient.sql("""
                SELECT id, code, type, owner_id, allow_negative, balance, created_at
                FROM accounts
                WHERE id = ANY(?)
                ORDER BY id
                FOR UPDATE
            """)
            // JdbcClient rejects a List for a positional parameter; pgjdbc binds Long[] as int8[].
            .param(accountIds.toArray(new Long[0]))
            .query(Account.class)
            .list();

        // Verify all accounts exist
        if (accounts.size() != accountIds.size()) {
            throw new ValidationException("One or more accounts not found");
        }

        Map<Long, Account> accountMap = new HashMap<>();
        for (Account acc : accounts) {
            accountMap.put(acc.id(), acc);
        }

        // 4. Compute new balances and check constraints
        Map<Long, Long> deltas = new HashMap<>();
        for (Leg leg : request.legs()) {
            deltas.merge(leg.accountId(), leg.amountPaise(), Long::sum);
        }

        for (Map.Entry<Long, Long> entry : deltas.entrySet()) {
            Account acc = accountMap.get(entry.getKey());
            long newBalance = acc.balance() + entry.getValue();

            if (!acc.allowNegative() && newBalance < 0) {
                throw new InsufficientFundsException(acc.code());
            }
        }

        // 5. Insert journal entry (with unique idempotency_key as race detector)
        long entryId;
        try {
            entryId = jdbcClient.sql("""
                    INSERT INTO journal_entries (type, reference_type, reference_id, idempotency_key, description)
                    VALUES (?, ?, ?, ?, ?)
                    RETURNING id
                """)
                .param(request.type())
                .param(request.referenceType())
                .param(request.referenceId())
                .param(request.idempotencyKey())
                .param(request.description())
                .query(Long.class)
                .single();
        } catch (DataIntegrityViolationException e) {
            // Concurrent caller already inserted with this idempotency key
            // This transaction will roll back; let caller retry and get the replayed result
            throw e;
        }

        // 6. Batch-insert postings and update balances atomically
        for (Leg leg : request.legs()) {
            jdbcClient.sql("""
                    INSERT INTO postings (entry_id, account_id, amount)
                    VALUES (?, ?, ?)
                """)
                .param(entryId)
                .param(leg.accountId())
                .param(leg.amountPaise())
                .update();
        }

        // Update all account balances in a single transaction
        for (Map.Entry<Long, Long> delta : deltas.entrySet()) {
            jdbcClient.sql("""
                    UPDATE accounts
                    SET balance = balance + ?
                    WHERE id = ?
                """)
                .param(delta.getValue())
                .param(delta.getKey())
                .update();
        }

        // Emit LedgerEntryPosted event (only on initial post, not replay)
        List<Events.LedgerEntryPosted.LegEvent> legEvents = request.legs().stream()
            .map(leg -> new Events.LedgerEntryPosted.LegEvent(
                accountMap.get(leg.accountId()).code(),
                leg.amountPaise()
            ))
            .toList();

        outboxWriter.write(
            "JournalEntry",
            String.valueOf(entryId),
            "LedgerEntryPosted",
            "ledger.entry-events",
            new Events.LedgerEntryPosted(
                entryId,
                request.type(),
                request.referenceType(),
                request.referenceId(),
                legEvents
            )
        );

        return new PostingResult(entryId, false);
    }

    /**
     * Validate a posting request before processing.
     */
    private void validateRequest(PostingRequest request) {
        if (request.legs() == null || request.legs().size() < 2) {
            throw new ValidationException("A posting must have at least 2 legs");
        }

        long sum = request.legs().stream()
            .mapToLong(Leg::amountPaise)
            .sum();
        if (sum != 0) {
            throw new ValidationException(
                "Posting legs must sum to 0, but sum is " + sum);
        }

        // Check for duplicate account IDs
        Set<Long> accountIds = new HashSet<>();
        for (Leg leg : request.legs()) {
            if (!accountIds.add(leg.accountId())) {
                throw new ValidationException(
                    "Duplicate account ID in posting: " + leg.accountId());
            }
        }

        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new ValidationException("idempotencyKey cannot be blank");
        }
    }
}
