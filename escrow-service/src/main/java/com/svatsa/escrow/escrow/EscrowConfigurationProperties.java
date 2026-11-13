package com.svatsa.escrow.escrow;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for escrow operations.
 * Prefix: escrow
 */
@Component
@ConfigurationProperties(prefix = "escrow")
public class EscrowConfigurationProperties {

    /**
     * Fee rate in basis points (default 500 = 5%).
     * Formula: fee = amount * feeBps / 10_000
     */
    private int feeBps = 500;

    public int getFeeBps() {
        return feeBps;
    }

    public void setFeeBps(int feeBps) {
        this.feeBps = feeBps;
    }
}
