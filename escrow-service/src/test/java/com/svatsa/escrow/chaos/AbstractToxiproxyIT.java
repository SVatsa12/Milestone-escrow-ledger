package com.svatsa.escrow.chaos;

import com.svatsa.escrow.test.AbstractIntegrationTest;

/**
 * Kept so existing tests keep their base class. All infrastructure (Postgres behind Toxiproxy,
 * Kafka, Redis, the fake payout provider, the invariant check after every test) now lives in
 * {@link AbstractIntegrationTest}; chaos scenarios extend {@link AbstractChaosIT}.
 */
public abstract class AbstractToxiproxyIT extends AbstractIntegrationTest {
}
