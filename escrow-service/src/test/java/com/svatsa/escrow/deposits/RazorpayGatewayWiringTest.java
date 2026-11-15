package com.svatsa.escrow.deposits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which Razorpay gateway the app wires, with the real application.yml / application-prod.yml and
 * the gateways' real conditions, in a context that holds nothing else (no DB, no containers).
 * The app once always used the fake (chaos/FINDINGS.md #27).
 */
@DisplayName("Razorpay gateway wiring")
@ExtendWith(OutputCaptureExtension.class)
class RazorpayGatewayWiringTest {

    @Configuration
    @EnableConfigurationProperties(RazorpayConfig.class)
    @Import({FakeRazorpayGateway.class, RazorpaySdkGateway.class})
    static class GatewaysOnly {
    }

    @Test
    @DisplayName("prod profile with keys → RazorpaySdkGateway, and no fake bean at all")
    void prodWiresTheSdkGateway() {
        try (var ctx = start(new String[]{"prod"}, Map.of(
                "RAZORPAY_KEY_ID", "rzp_test_wiring", "RAZORPAY_KEY_SECRET", "s", "RAZORPAY_WEBHOOK_SECRET", "w"))) {
            assertThat(ctx.getBean(RazorpayGateway.class)).isInstanceOf(RazorpaySdkGateway.class);
            assertThat(ctx.getBeansOfType(FakeRazorpayGateway.class)).isEmpty();
        }
    }

    @Test
    @DisplayName("prod profile without keys refuses to start (never a silent fallback to the fake)")
    void prodWithoutKeysFailsFast() {
        assertThatThrownBy(() -> start(new String[]{"prod"}, Map.of()).close())
            .hasStackTraceContaining("RAZORPAY_KEY_ID");
    }

    @Test
    @DisplayName("default profile with no key (local dev, tests) → FakeRazorpayGateway, and it says so at startup")
    void defaultWithoutKeyUsesTheFake(CapturedOutput output) {
        try (var ctx = start(new String[0], Map.of())) {
            assertThat(ctx.getBean(RazorpayGateway.class)).isInstanceOf(FakeRazorpayGateway.class);
        }
        assertThat(output).contains("razorpay.gateway=FAKE").contains("nothing reaches Razorpay");
    }

    @Test
    @DisplayName("default profile with all three set (docker compose with keys) → RazorpaySdkGateway")
    void allThreeSetUsesTheSdk(CapturedOutput output) {
        try (var ctx = start(new String[0], Map.of(
                "RAZORPAY_KEY_ID", "rzp_test_compose1234", "RAZORPAY_KEY_SECRET", "s", "RAZORPAY_WEBHOOK_SECRET", "w"))) {
            assertThat(ctx.getBean(RazorpayGateway.class)).isInstanceOf(RazorpaySdkGateway.class);
        }
        assertThat(output).contains("razorpay.gateway=RAZORPAY_SDK").contains("rzp_test_****1234")
            .doesNotContain("rzp_test_compose1234");
    }

    @Test
    @DisplayName("only a webhook secret (signed webhooks against the fake, as the tests do) → FakeRazorpayGateway")
    void webhookSecretAloneStillUsesTheFake() {
        try (var ctx = start(new String[0], Map.of("RAZORPAY_WEBHOOK_SECRET", "w"))) {
            assertThat(ctx.getBean(RazorpayGateway.class)).isInstanceOf(FakeRazorpayGateway.class);
        }
    }

    @Test
    @DisplayName("partly configured → refuses to start, naming exactly the missing variables")
    void partlyConfiguredFailsNamingTheMissingOnes() {
        assertThatThrownBy(() -> start(new String[0], Map.of("RAZORPAY_KEY_ID", "rzp_test_x")).close())
            .hasStackTraceContaining("Razorpay is partly configured: RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET are not set");
        assertThatThrownBy(() -> start(new String[0], Map.of("RAZORPAY_KEY_SECRET", "s", "RAZORPAY_WEBHOOK_SECRET", "w")).close())
            .hasStackTraceContaining("Razorpay is partly configured: RAZORPAY_KEY_ID is not set");
        assertThatThrownBy(() -> start(new String[0], Map.of("RAZORPAY_KEY_ID", "rzp_test_x", "RAZORPAY_KEY_SECRET", "s")).close())
            .hasStackTraceContaining("Razorpay is partly configured: RAZORPAY_WEBHOOK_SECRET is not set");
    }

    private static ConfigurableApplicationContext start(String[] profiles, Map<String, Object> properties) {
        SpringApplication app = new SpringApplication(GatewaysOnly.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles(profiles);
        app.setDefaultProperties(properties);
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        return app.run();
    }
}
