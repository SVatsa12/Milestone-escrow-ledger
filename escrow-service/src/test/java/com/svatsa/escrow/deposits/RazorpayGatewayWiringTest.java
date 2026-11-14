package com.svatsa.escrow.deposits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    @DisplayName("default profile with no key (local dev, tests) → FakeRazorpayGateway")
    void defaultWithoutKeyUsesTheFake() {
        try (var ctx = start(new String[0], Map.of())) {
            assertThat(ctx.getBean(RazorpayGateway.class)).isInstanceOf(FakeRazorpayGateway.class);
        }
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
