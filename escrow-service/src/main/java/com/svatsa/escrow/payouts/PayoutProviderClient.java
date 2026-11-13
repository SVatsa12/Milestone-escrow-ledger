package com.svatsa.escrow.payouts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.UUID;

/**
 * Client for the mock payout provider.
 * Handles timeout classification: 200/202 → success, 4xx/5xx → fail, timeout → UNKNOWN.
 * 
 * All errors are logged but don't throw; classification is returned to the caller
 * so PayoutService can decide the state transition.
 */
@Component
public class PayoutProviderClient {
    private static final Logger log = LoggerFactory.getLogger(PayoutProviderClient.class);
    
    private final org.springframework.web.client.RestTemplate restTemplate;
    private final PayoutProviderProperties config;
    
    public PayoutProviderClient(RestTemplateBuilder builder, PayoutProviderProperties config) {
        this.config = config;
        this.restTemplate = builder
            .setConnectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
            .setReadTimeout(Duration.ofSeconds(config.readTimeoutSeconds()))
            .build();
    }
    
    /**
     * Request a payout from the provider.
     * Returns the provider's HTTP status code and response. Timeouts → null.
     */
    public ProviderResponse requestPayout(UUID payoutId, String beneficiary, long amountPaise) {
        try {
            String url = config.baseUrl() + "/v1/payouts";
            var request = new PayoutRequest(beneficiary, amountPaise);
            
            log.info("Requesting payout from provider: id={}, amount={} paise", payoutId, amountPaise);
            
            var response = restTemplate.postForEntity(url, request, String.class);
            
            log.info("Provider returned {} for payout {}", response.getStatusCode().value(), payoutId);
            return new ProviderResponse(response.getStatusCode().value(), response.getBody());
            
        } catch (HttpServerErrorException e) {
            // 5xx = server error (UNKNOWN)
            log.warn("Provider error for payout {}: {}", payoutId, e.getStatusCode());
            return null;  // Classify as UNKNOWN (timeout-like)
        } catch (HttpClientErrorException e) {
            // 4xx errors = provider said no (FAILED)
            log.warn("Provider rejected payout {}: {} {}", payoutId, e.getStatusCode(), e.getStatusText());
            return new ProviderResponse(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            // ResourceAccessException (timeout/connection) or other RestClientException
            log.warn("Provider error for payout {}: {}", payoutId, e.getMessage());
            return null;  // Classify as UNKNOWN
        }
    }
    
    /**
     * Check the status of a payout at the provider.
     * Returns the payout's current status or null if UNKNOWN.
     */
    public ProviderStatusResponse checkStatus(UUID payoutId) {
        try {
            String url = config.baseUrl() + "/v1/payouts/by-key/" + payoutId;
            log.debug("Checking payout status at provider: {}", payoutId);
            
            var response = restTemplate.getForEntity(url, ProviderPayoutRecord.class);
            return new ProviderStatusResponse(200, response.getBody());
            
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Payout not found at provider: {}", payoutId);
            return new ProviderStatusResponse(404, null);
        } catch (RestClientException e) {
            log.warn("Error checking status at provider for {}: {}", payoutId, e.getMessage());
            return null;  // UNKNOWN
        }
    }
    
    /**
     * Cancel a payout at the provider (tombstone).
     * Returns true if cancelled, false if already settled.
     */
    public boolean cancelPayout(UUID payoutId) {
        try {
            String url = config.baseUrl() + "/v1/payouts/by-key/" + payoutId + "/cancel";
            log.info("Cancelling payout at provider: {}", payoutId);
            
            var response = restTemplate.postForEntity(url, null, String.class);
            return response.getStatusCode().value() == 200;
            
        } catch (HttpClientErrorException.Conflict e) {
            log.info("Payout already settled at provider: {}", payoutId);
            return false;
        } catch (RestClientException e) {
            log.warn("Error cancelling payout at provider {}: {}", payoutId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Provider response DTO.
     */
    public record ProviderResponse(int statusCode, String body) {}
    
    /**
     * Provider status check response.
     */
    public record ProviderStatusResponse(int statusCode, ProviderPayoutRecord payout) {}
    
    /**
     * Mock provider's payout record.
     */
    public record ProviderPayoutRecord(String status, long amountPaise, String beneficiary) {}
    
    /**
     * Payout request to provider.
     */
    public record PayoutRequest(String beneficiary, long amountPaise) {}
}

/**
 * Configuration for the payout provider.
 */
@ConfigurationProperties(prefix = "payouts.provider")
record PayoutProviderProperties(
    String baseUrl,
    int connectTimeoutSeconds,
    int readTimeoutSeconds
) {}
