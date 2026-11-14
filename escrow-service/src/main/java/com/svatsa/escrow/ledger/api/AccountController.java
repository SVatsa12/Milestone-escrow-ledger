package com.svatsa.escrow.ledger.api;

import com.svatsa.escrow.common.Actor;
import com.svatsa.escrow.common.Authz;
import com.svatsa.escrow.common.ValidationException;
import com.svatsa.escrow.idempotency.IdempotencyService;
import com.svatsa.escrow.ledger.Account;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.PostingRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for ledger accounts and transfers.
 */
@RestController
@RequestMapping("/api/v1")
public class AccountController {

    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;
    private final JdbcClient jdbcClient;

    public AccountController(
        AccountRepository accountRepository,
        LedgerService ledgerService,
        IdempotencyService idempotencyService,
        JdbcClient jdbcClient
    ) {
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
        this.jdbcClient = jdbcClient;
    }

    /**
     * Create a new account. ADMIN only (403 FORBIDDEN otherwise, 401 without actor headers).
     */
    @PostMapping("/accounts")
    public ResponseEntity<Account> createAccount(
        Actor actor,
        @RequestBody CreateAccountRequest request
    ) {
        Authz.requireAdmin(actor, "create an account");

        String code = switch (request.type()) {
            case CLIENT_WALLET -> "CLIENT_WALLET:" + request.ownerId();
            case FREELANCER_WALLET -> "FREELANCER_WALLET:" + request.ownerId();
            default -> throw new ValidationException("Cannot create account of type " + request.type());
        };

        Account account = accountRepository.create(code, request.type(), request.ownerId(), false);
        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }

    /**
     * Get account details by ID.
     */
    @GetMapping("/accounts/{id}")
    public ResponseEntity<Map<String, Object>> getAccount(@PathVariable long id) {
        Account account = accountRepository.findById(id)
            .orElseThrow(() -> new com.svatsa.escrow.common.DomainException("NOT_FOUND",
                HttpStatus.NOT_FOUND, "Account not found: " + id));

        Map<String, Object> response = new HashMap<>();
        response.put("id", account.id());
        response.put("code", account.code());
        response.put("balance", account.balance());

        return ResponseEntity.ok(response);
    }

    /**
     * Look an account up by its business code, e.g. CLIENT_WALLET:42 (wallets are created by a
     * DB trigger when a user is created, so callers know the code but not the id).
     */
    @GetMapping("/accounts/by-code/{code}")
    public ResponseEntity<Map<String, Object>> getAccountByCode(@PathVariable String code) {
        Account account = accountRepository.findByCode(code)
            .orElseThrow(() -> new com.svatsa.escrow.common.DomainException("NOT_FOUND",
                HttpStatus.NOT_FOUND, "Account not found: " + code));
        Map<String, Object> response = new HashMap<>();
        response.put("id", account.id());
        response.put("code", account.code());
        response.put("balance", account.balance());
        return ResponseEntity.ok(response);
    }

    /**
     * Transfer money between any two accounts (idempotent). ADMIN only: it can move money out of
     * anyone's wallet, so a non-admin is 403 FORBIDDEN before anything is checked or posted.
     */
    @PostMapping("/transfers")
    public ResponseEntity<Map<String, Object>> transfer(
        Actor actor,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody TransferRequest request
    ) {
        Authz.requireAdmin(actor, "transfer between accounts");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ValidationException("Idempotency-Key header is required");
        }

        if (request.amountPaise() <= 0) {
            throw new ValidationException("Amount must be positive");
        }

        if (request.fromAccountId() == request.toAccountId()) {
            throw new ValidationException("Cannot transfer to the same account");
        }

        return idempotencyService.execute(
            idempotencyKey,
            "transfer-api",
            request,
            () -> {
                // Post the transfer
                var result = ledgerService.post(new PostingRequest(
                    "TRANSFER",
                    "TRANSFER",
                    idempotencyKey,
                    "transfer:" + idempotencyKey,
                    request.memo(),
                    List.of(
                        new Leg(request.fromAccountId(), -request.amountPaise()),
                        new Leg(request.toAccountId(), request.amountPaise())
                    )
                ));

                Map<String, Object> response = new HashMap<>();
                response.put("entryId", result.entryId());
                response.put("replayed", result.replayed());

                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            }
        );
    }

    /**
     * Simulate a deposit (for dev/testing only, idempotent). ADMIN only.
     */
    @PostMapping("/admin/deposits/simulate")
    public ResponseEntity<Map<String, Object>> simulateDeposit(
        Actor actor,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody SimulateDepositRequest request
    ) {
        Authz.requireAdmin(actor, "simulate a deposit");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ValidationException("Idempotency-Key header is required");
        }

        if (request.amountPaise() <= 0) {
            throw new ValidationException("Amount must be positive");
        }

        return idempotencyService.execute(
            idempotencyKey,
            "deposit-simulate-api",
            request,
            () -> {
                // Get or create the EXTERNAL_BANK:RAZORPAY account
                Account razorpayAccount = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY")
                    .orElseThrow(() -> new ValidationException("EXTERNAL_BANK:RAZORPAY account not found"));

                // Get the target account
                Account clientAccount = accountRepository.findById(request.clientAccountId())
                    .orElseThrow(() -> new ValidationException("Client account not found"));

                // Post the deposit
                var result = ledgerService.post(new PostingRequest(
                    // Not a Razorpay DEPOSIT (keyed deposit:rzp:{paymentId}, checked by I10):
                    // a dev-only credit keyed by the caller's request.
                    "SIMULATED_DEPOSIT",
                    "SIMULATED_DEPOSIT",
                    idempotencyKey,
                    "deposit:simulate:" + idempotencyKey,
                    "Simulated deposit",
                    List.of(
                        new Leg(razorpayAccount.id(), -request.amountPaise()),
                        new Leg(clientAccount.id(), request.amountPaise())
                    )
                ));

                Map<String, Object> response = new HashMap<>();
                response.put("entryId", result.entryId());
                response.put("replayed", result.replayed());

                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            }
        );
    }

    /**
     * Get postings for an account (keyset pagination).
     */
    @GetMapping("/accounts/{id}/postings")
    public ResponseEntity<List<Map<String, Object>>> getPostings(
        @PathVariable long id,
        @RequestParam(defaultValue = "-1") long cursor,
        @RequestParam(defaultValue = "50") int limit
    ) {
        // Keyset pagination on postings.id desc
        String sql;
        Object[] params;

        if (cursor < 0) {
            sql = """
                SELECT id, amount, created_at
                FROM postings
                WHERE account_id = ?
                ORDER BY id DESC
                LIMIT ?
            """;
            params = new Object[]{id, limit + 1};
        } else {
            sql = """
                SELECT id, amount, created_at
                FROM postings
                WHERE account_id = ? AND id < ?
                ORDER BY id DESC
                LIMIT ?
            """;
            params = new Object[]{id, cursor, limit + 1};
        }

        List<Map<String, Object>> results = new java.util.ArrayList<>();
        jdbcClient.sql(sql)
            .params(params)
            .query((rs, rowNum) -> {
                Map<String, Object> row = new HashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("amount", rs.getLong("amount"));
                row.put("created_at", rs.getTimestamp("created_at"));
                results.add(row);
                return row;
            })
            .list();

        return ResponseEntity.ok(results);
    }
}
