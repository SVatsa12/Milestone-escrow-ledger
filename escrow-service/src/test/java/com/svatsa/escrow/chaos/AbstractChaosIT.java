package com.svatsa.escrow.chaos;

import org.junit.jupiter.api.Tag;

/**
 * Base class for the chaos scenarios (tag "chaos", run with ./mvnw verify -Pchaos).
 * All infrastructure lives in {@link AbstractToxiproxyIT}.
 */
@Tag("chaos")
public abstract class AbstractChaosIT extends AbstractToxiproxyIT {
}
