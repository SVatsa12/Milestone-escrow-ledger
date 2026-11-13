package com.svatsa.escrow.outbox;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates UUIDv7: time-ordered, sortable UUIDs suitable for database usage.
 *
 * UUIDv7 structure (RFC 4122 draft):
 * - 48 bits: Unix timestamp in milliseconds
 * - 4 bits: version (0111 = 7)
 * - 12 bits: random
 * - 2 bits: variant (10)
 * - 62 bits: random
 *
 * This ensures:
 * 1. Temporal ordering: earlier events have lower UUIDs
 * 2. Database efficiency: sequential inserts avoid page splits
 * 3. No central coordination: random bits prevent collisions
 */
public class UUIDv7Generator {
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Generates a new UUIDv7 at the current time.
     */
    public static UUID generate() {
        long now = System.currentTimeMillis();
        return generateAtTime(now);
    }

    /**
     * Generates a UUIDv7 at a specific millisecond timestamp.
     * (Useful for testing time travel.)
     */
    public static UUID generateAtTime(long timestampMs) {
        // 48-bit timestamp in milliseconds
        long timestamp = timestampMs & 0xFFFFFFFFFFFFL;

        // 12 random bits (4-bit version 7 + 12-bit random = 16 bits)
        int randomHighBits = RANDOM.nextInt(0x1000); // 12 bits

        // 62 random bits (2-bit variant 10 + 62-bit random)
        long randomLow = RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL;

        // Construct the UUID
        // Bits 63-48: timestamp bits 47-32
        long mostSigBits = (timestamp << 16) | (0x7000 | randomHighBits);

        // Bits 63-62: variant 10, bits 61-0: random
        long leastSigBits = (0x8000000000000000L | randomLow);

        return new UUID(mostSigBits, leastSigBits);
    }
}
