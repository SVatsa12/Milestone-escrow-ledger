package com.svatsa.escrow.payouts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Client for the payout provider.
 *
 * Classification is returned to the caller; nothing here retries. A timeout, connection
 * reset or 5xx comes back as {@code null} (UNKNOWN), because the provider may or may not
 * have acted on the request (invariant 12).
 *
 * Every POST carries {@code X-Payout-Idempotency-Key = payoutId}, so the provider can
 * dedupe, and the recovery job can look the payout up by that key.
 */
@Component
@EnableConfigurationProperties(PayoutProviderProperties.class)
public class PayoutProviderClient {
    private static final Logger log = LoggerFactory.getLogger(PayoutProviderClient.class);

    public static final String IDEMPOTENCY_HEADER = "X-Payout-Idempotency-Key";

    private final RestTemplate restTemplate;
    private final PayoutProviderProperties config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PayoutProviderClient(RestTemplateBuilder builder, PayoutProviderProperties config) {
        this.config = config;
        this.restTemplate = builder
            .connectTimeout(config.connectTimeout())
            .readTimeout(config.readTimeout())
            .build();
    }

    /**
     * Request a payout from the provider. Returns the provider's status code and body,
     * or {@code null} when the outcome is unknown (timeout, IO error, 5xx).
     */
    public ProviderResponse requestPayout(UUID payoutId, String beneficiary, long amountPaise) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(IDEMPOTENCY_HEADER, payoutId.toString());
            var request = new HttpEntity<>(new PayoutRequest(beneficiary, amountPaise), headers);

            log.info("provider.request payoutId={} amountPaise={}", payoutId, amountPaise);
            var response = restTemplate.postForEntity(config.providerBaseUrl() + "/v1/payouts", request, String.class);
            log.info("provider.response payoutId={} status={}", payoutId, response.getStatusCode().value());
            return new ProviderResponse(response.getStatusCode().value(), response.getBody());
        } catch (HttpServerErrorException e) {
            log.warn("provider.error payoutId={} status={}", payoutId, e.getStatusCode().value());
            return null;
        } catch (HttpClientErrorException e) {
            log.warn("provider.rejected payoutId={} status={}", payoutId, e.getStatusCode().value());
            return new ProviderResponse(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            log.warn("provider.unreachable payoutId={} error={}", payoutId, e.getMessage());
            return null;
        }
    }

    /**
     * Look a payout up at the provider by idempotency key.
     * Returns 200 + record, 404 + null, or {@code null} when the check itself failed.
     */
    public ProviderStatusResponse checkStatus(UUID payoutId) {
        try {
            String url = config.providerBaseUrl() + "/v1/payouts/by-key/" + payoutId;
            var response = restTemplate.getForEntity(url, ProviderPayoutRecord.class);
            return new ProviderStatusResponse(200, response.getBody());
        } catch (HttpClientErrorException.NotFound e) {
            return new ProviderStatusResponse(404, null);
        } catch (RestClientException e) {
            log.warn("provider.status-check-failed payoutId={} error={}", payoutId, e.getMessage());
            return null;
        }
    }

    /**
     * Tombstone the key at the provider so a late-arriving original request is rejected.
     * Returns the provider's final status for the key: CANCELLED when the tombstone took,
     * SUCCESS / FAILED when the payout had already been settled (409), or {@code null}
     * when the outcome is unknown.
     */
    public String cancelPayout(UUID payoutId) {
        try {
            String url = config.providerBaseUrl() + "/v1/payouts/by-key/" + payoutId + "/cancel";
            log.info("provider.cancel payoutId={}", payoutId);
            var response = restTemplate.postForEntity(url, null, ProviderPayoutRecord.class);
            return response.getBody() != null ? response.getBody().status() : "CANCELLED";
        } catch (HttpClientErrorException.Conflict e) {
            try {
                var record = objectMapper.readValue(e.getResponseBodyAsString(), ProviderPayoutRecord.class);
                log.info("provider.cancel-conflict payoutId={} status={}", payoutId, record.status());
                return record.status();
            } catch (Exception parse) {
                return null;
            }
        } catch (RestClientException e) {
            log.warn("provider.cancel-failed payoutId={} error={}", payoutId, e.getMessage());
            return null;
        }
    }

    /**
     * GET /v1/settlements?date=: every SUCCESS payout the provider settled that Asia/Kolkata day.
     * Throws if the provider can't be asked: reconciliation must fail, not report a clean day.
     */
    public List<ProviderSettlement> settlements(java.time.LocalDate date) {
        String url = config.providerBaseUrl() + "/v1/settlements?date=" + date;
        ProviderSettlement[] body = restTemplate.getForObject(url, ProviderSettlement[].class);
        return body == null ? List.of() : List.of(body);
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderSettlement(String idempotencyKey, String status, long amountPaise, java.time.Instant settledAt) {}

    public record ProviderResponse(int statusCode, String body) {}

    public record ProviderStatusResponse(int statusCode, ProviderPayoutRecord payout) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderPayoutRecord(String status, long amountPaise, String beneficiary) {}

    public record PayoutRequest(String beneficiary, long amountPaise) {}
}

/**
 * Bound from {@code payouts.*} in application.yml.
 */
@ConfigurationProperties(prefix = "payouts")
record PayoutProviderProperties(
    String providerBaseUrl,
    Duration connectTimeout,
    Duration readTimeout
) {}
