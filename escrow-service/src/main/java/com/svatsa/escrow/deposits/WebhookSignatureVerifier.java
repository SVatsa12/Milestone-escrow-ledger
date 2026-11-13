package com.svatsa.escrow.deposits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Verifies Razorpay webhook signatures using HMAC-SHA256.
 * 
 * Razorpay signs webhooks with:
 *   X-Razorpay-Signature: hex(HMAC-SHA256(raw_request_body, webhook_secret))
 * 
 * Safety:
 * - Always verifies against RAW bytes, never re-serialised JSON
 * - Uses constant-time comparison (MessageDigest.isEqual) to prevent timing attacks
 */
@Component
public class WebhookSignatureVerifier {
    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureVerifier.class);
    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Verify a webhook signature.
     * 
     * @param rawBody raw request body bytes (never re-serialised JSON)
     * @param signature hex-encoded signature from X-Razorpay-Signature header
     * @param secret webhook secret from Razorpay dashboard
     * @return true if signature is valid, false otherwise
     */
    public boolean verify(byte[] rawBody, String signature, String secret) {
        try {
            // Compute HMAC-SHA256
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                0,
                secret.getBytes(StandardCharsets.UTF_8).length,
                ALGORITHM
            );
            mac.init(keySpec);
            byte[] computed = mac.doFinal(rawBody);

            // Convert to hex
            String computedHex = bytesToHex(computed);

            // Constant-time comparison to prevent timing attacks
            // Using Java's MessageDigest.isEqual is not available, so use a simple constant-time impl
            return constantTimeEquals(signature, computedHex);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Failed to verify webhook signature: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Convert byte array to hex string.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     * Returns true if both strings are equal, false otherwise.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);

        int result = 0;
        
        // Compare lengths in constant time
        result |= aBytes.length ^ bBytes.length;
        
        // Compare content in constant time
        int minLength = Math.min(aBytes.length, bBytes.length);
        for (int i = 0; i < minLength; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        
        return result == 0;
    }
}
