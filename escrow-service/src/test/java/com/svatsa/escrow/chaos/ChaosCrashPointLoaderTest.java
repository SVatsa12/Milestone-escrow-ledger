package com.svatsa.escrow.chaos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** The crash point comes from chaos.crash-point, and only under the chaos profile. */
@DisplayName("Chaos crash point configuration")
class ChaosCrashPointLoaderTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ChaosCrashPointLoader.class);

    @AfterEach
    void disarm() {
        ChaosConfig.setCrashPoint(ChaosConfig.CrashPoint.NONE);
    }

    @Test
    @DisplayName("chaos profile + chaos.crash-point=AFTER_RESERVE arms AFTER_RESERVE")
    void chaosProfileArmsTheConfiguredPoint() {
        runner.withPropertyValues("spring.profiles.active=chaos", "chaos.crash-point=AFTER_RESERVE")
            .run(ctx -> assertThat(ChaosConfig.getCrashPoint()).isEqualTo(ChaosConfig.CrashPoint.AFTER_RESERVE));
    }

    @Test
    @DisplayName("without the chaos profile the property is ignored: nothing is armed")
    void withoutTheProfileNothingIsArmed() {
        runner.withPropertyValues("chaos.crash-point=AFTER_PROVIDER_CALL")
            .run(ctx -> {
                assertThat(ctx).doesNotHaveBean(ChaosCrashPointLoader.class);
                assertThat(ChaosConfig.getCrashPoint()).isEqualTo(ChaosConfig.CrashPoint.NONE);
            });
    }
}
