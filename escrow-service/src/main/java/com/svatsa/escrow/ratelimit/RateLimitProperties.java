package com.svatsa.escrow.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Rate limiting configuration.
 * 
 * Controls request limits per actor per minute for money-moving endpoints.
 * Uses Redis for atomic tracking. Fails open if Redis is unavailable.
 */
@Component
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitProperties {
    
    /**
     * Requests per minute limit for transfers and other money-moving POSTs.
     * Default: 30 req/min.
     */
    private int transferRequestsPerMinute = 30;
    
    /**
     * Requests per minute limit for payout requests.
     * Default: 5 req/min.
     */
    private int payoutRequestsPerMinute = 5;
    
    public int getTransferRequestsPerMinute() {
        return transferRequestsPerMinute;
    }
    
    public void setTransferRequestsPerMinute(int transferRequestsPerMinute) {
        this.transferRequestsPerMinute = transferRequestsPerMinute;
    }
    
    public int getPayoutRequestsPerMinute() {
        return payoutRequestsPerMinute;
    }
    
    public void setPayoutRequestsPerMinute(int payoutRequestsPerMinute) {
        this.payoutRequestsPerMinute = payoutRequestsPerMinute;
    }
}
