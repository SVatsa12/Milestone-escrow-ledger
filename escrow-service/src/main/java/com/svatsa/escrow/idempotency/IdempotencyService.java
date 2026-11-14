package com.svatsa.escrow.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.svatsa.escrow.common.DomainException;
import com.svatsa.escrow.common.ValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * HTTP-level idempotency service.
 * 
 * Implements idempotent request handling by storing the response of the first request
 * and replaying it for subsequent identical requests. This ensures that retries never
 * cause side effects to happen twice.
 * 
 * Concurrency safety:
 * - The idempotency_keys table has a UNIQUE constraint on (key).
 * - When two transactions race to insert the same key:
 *   1. First transaction's INSERT succeeds (1 row inserted)
 *   2. Second transaction blocks on the UNIQUE index
 *   3. First transaction commits, storing the response
 *   4. Second transaction's INSERT fails (0 rows inserted, sees existing row)
 *   5. Second transaction retrieves and returns the existing response
 * - If the action throws a DomainException, the transaction rolls back
 *   and the idempotency key remains unused, so the retry is safe.
 */
@Service
public class IdempotencyService {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();
    }

    /**
     * Execute an action idempotently.
     * 
     * @param key the idempotency key (from header)
     * @param scope a scope to namespace the key (e.g., "transfer-api", "deposit-api")
     * @param requestBody the request body (for hashing and deduplication)
     * @param action a supplier that performs the actual work and returns a ResponseEntity
     * @return the response (fresh or replayed)
     * @throws ValidationException if key is reused with a different body
     */
    @Transactional
    public <T> ResponseEntity<T> execute(
        String key,
        String scope,
        Object requestBody,
        Supplier<ResponseEntity<T>> action
    ) {
        if (key == null || key.isBlank()) {
            throw new ValidationException("Idempotency-Key header is required");
        }

        String requestHash = hashRequest(scope, requestBody);

        // Try to insert a new row (owned by this transaction)
        int inserted = jdbcClient.sql("""
                INSERT INTO idempotency_keys (key, scope, request_hash, response_status, response_body)
                VALUES (?, ?, ?, 0, '{}')
                ON CONFLICT (key) DO NOTHING
            """)
            .param(key)
            .param(scope)
            .param(requestHash)
            .update();

        if (inserted > 0) {
            // We own the key; run the action
            try {
                ResponseEntity<T> response = action.get();

                // Store the response
                String responseBody = objectMapper.writeValueAsString(response.getBody());
                int status = response.getStatusCode().value();

                jdbcClient.sql("""
                        UPDATE idempotency_keys
                        SET response_status = ?, response_body = ?::jsonb
                        WHERE key = ?
                    """)
                    .param(status)
                    .param(responseBody)
                    .param(key)
                    .update();

                return response;
            } catch (DomainException e) {
                // If the action fails with a business error, roll back the entire transaction
                // (including the idempotency_keys INSERT). This allows the retry to be processed normally.
                // This is safe because a rejected request moved no money, so the retry is safe.
                throw e;
            } catch (Exception e) {
                // Unexpected error; still roll back
                throw new RuntimeException("Error executing idempotent action", e);
            }
        } else {
            // Key already exists; check if the hash matches
            IdempotencyRecord existing = jdbcClient.sql("""
                    SELECT key, scope, request_hash, response_status, response_body
                    FROM idempotency_keys
                    WHERE key = ?
                """)
                .param(key)
                .query((rs, rowNum) -> new IdempotencyRecord(
                    rs.getString("key"),
                    rs.getString("scope"),
                    rs.getString("request_hash"),
                    rs.getInt("response_status"),
                    rs.getString("response_body")
                ))
                .single();

            if (!requestHash.equals(existing.requestHash)) {
                // P3 / AGENTS.md: 422 IDEMPOTENCY_KEY_REUSED (this used to be a 400 VALIDATION_FAILED).
                throw new DomainException("IDEMPOTENCY_KEY_REUSED", org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Idempotency key reused with a different request body");
            }

            // Parse and return the stored response
            try {
                @SuppressWarnings("unchecked")
                T body = (T) objectMapper.readValue(existing.responseBody, Object.class);
                return ResponseEntity
                    .status(existing.responseStatus)
                    .header("Idempotent-Replayed", "true")
                    .body(body);
            } catch (Exception e) {
                throw new RuntimeException("Error deserializing cached response", e);
            }
        }
    }

    /**
     * Compute a SHA-256 hash of the request scope and body.
     * 
     * This hash is used to detect when the same idempotency key is used with
     * a different request body, which is an error (IDEMPOTENCY_KEY_REUSED).
     */
    private String hashRequest(String scope, Object requestBody) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            // Hash the scope first
            md.update(scope.getBytes(StandardCharsets.UTF_8));

            // Hash the request body as canonical JSON
            String canonicalJson = objectMapper.writeValueAsString(requestBody);
            md.update(canonicalJson.getBytes(StandardCharsets.UTF_8));

            byte[] hash = md.digest();
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        } catch (Exception e) {
            throw new RuntimeException("Error hashing request", e);
        }
    }

    /**
     * Internal record for cached idempotency data.
     */
    private record IdempotencyRecord(
        String key,
        String scope,
        String requestHash,
        int responseStatus,
        String responseBody
    ) {
    }
}
