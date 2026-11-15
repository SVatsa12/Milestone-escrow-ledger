package com.svatsa.escrow.deposits;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Razorpay configuration (razorpay.* in application.yml, from RAZORPAY_KEY_ID,
 * RAZORPAY_KEY_SECRET and RAZORPAY_WEBHOOK_SECRET). Never hardcode keys.
 *
 * <ul>
 *   <li>No API keys: FakeRazorpayGateway. A webhook secret alone is allowed, so signed webhooks
 *       can be tested locally against the fake.</li>
 *   <li>Key id or key secret set: test mode through RazorpaySdkGateway, and then ALL THREE are
 *       required. Startup fails naming the missing ones, never a silent fallback to the fake.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "razorpay")
public record RazorpayConfig(
    String keyId,
    String keySecret,
    String webhookSecret
) {
    @ConstructorBinding
    public RazorpayConfig {}

    /** Blank, or a placeholder Spring left unresolved (prod has no defaults: "${RAZORPAY_KEY_ID}"). */
    static boolean missing(String value) {
        return value == null || value.isBlank() || value.startsWith("${");
    }

    /** True when either API key is configured: the app is meant to talk to Razorpay. */
    public boolean wantsRealGateway() {
        return !missing(keyId) || !missing(keySecret);
    }

    /** The environment variables still unset, in a stable order. */
    public List<String> missingVariables() {
        List<String> names = new ArrayList<>();
        if (missing(keyId)) names.add("RAZORPAY_KEY_ID");
        if (missing(keySecret)) names.add("RAZORPAY_KEY_SECRET");
        if (missing(webhookSecret)) names.add("RAZORPAY_WEBHOOK_SECRET");
        return names;
    }

    /** Fails startup when Razorpay is only partly configured; names exactly what is missing. */
    public void requireCompleteForRealGateway() {
        List<String> names = missingVariables();
        if (!names.isEmpty()) {
            throw new IllegalStateException("Razorpay is partly configured: " + String.join(", ", names)
                + (names.size() == 1 ? " is" : " are") + " not set. Set RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET and "
                + "RAZORPAY_WEBHOOK_SECRET for Razorpay test mode, or leave the two keys unset for the fake gateway.");
        }
    }
}
