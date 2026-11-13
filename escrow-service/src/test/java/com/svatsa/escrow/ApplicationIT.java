package com.svatsa.escrow;

import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke test to verify the application boots successfully.
 * Full integration tests with Testcontainers run via docker-compose in CI.
 */
class ApplicationIT extends AbstractIntegrationTest {

    @Test
    void applicationBoots() {
        // If we got here, the context loaded and invariants were checked
    }

}
