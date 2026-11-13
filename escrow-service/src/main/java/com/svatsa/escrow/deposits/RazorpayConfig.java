package com.svatsa.escrow.deposits;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Razorpay configuration properties.
 * Loaded from application.yml under escrow.razorpay.*.
 * Never hardcode keys; always read from environment variables.
 */
@ConfigurationProperties(prefix = "razorpay")
public record RazorpayConfig(
    String keyId,
    String keySecret,
    String webhookSecret
) {
    @ConstructorBinding
    public RazorpayConfig {}
}
