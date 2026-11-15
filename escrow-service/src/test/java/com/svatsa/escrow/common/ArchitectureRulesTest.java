package com.svatsa.escrow.common;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGENTS.md rules that a grep found broken once, enforced on escrow-service's main classes so they
 * can't come back.
 */
@DisplayName("Architecture rules")
class ArchitectureRulesTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
        .importPackages("com.svatsa.escrow");

    private static final Set<String> TIME_TYPES = Set.of(
        "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
        "java.time.ZonedDateTime", "java.time.OffsetDateTime");

    /** Reads wall-clock time without the injected Clock. {@code X.now(clock)} is fine. */
    private static final DescribedPredicate<JavaMethodCall> READS_SYSTEM_TIME = new DescribedPredicate<>(
            "read the system time instead of the injected Clock") {
        @Override
        public boolean test(JavaMethodCall call) {
            String owner = call.getTarget().getOwner().getName();
            String name = call.getTarget().getName();
            if (owner.equals("java.lang.System")) {
                return name.equals("currentTimeMillis");
            }
            if (owner.equals("java.time.Clock")) {
                return name.startsWith("system");
            }
            return TIME_TYPES.contains(owner) && name.equals("now")
                && call.getTarget().getRawParameterTypes().stream().noneMatch(p -> p.isEquivalentTo(Clock.class));
        }
    };

    private static com.tngtech.archunit.lang.ArchRule noSystemTime() {
        return noClasses()
            .that().doNotHaveFullyQualifiedName(ClockConfig.class.getName())
            .should().callMethodWhere(READS_SYSTEM_TIME)
            .because("AGENTS.md: time always comes from an injected java.time.Clock, so tests can move it");
    }

    @Test
    @DisplayName("time comes from the injected Clock: no now()/currentTimeMillis outside ClockConfig")
    void noSystemTimeOutsideClockConfig() {
        noSystemTime().check(MAIN);
    }

    @Test
    @DisplayName("the clock rule catches each forbidden call and allows now(clock)")
    void clockRuleCatchesViolations() {
        for (Class<?> offender : new Class<?>[] {UsesInstantNow.class, UsesLocalDateNowZone.class, UsesCurrentTimeMillis.class}) {
            assertThatThrownBy(() -> noSystemTime().check(new ClassFileImporter().importClasses(offender)))
                .as(offender.getSimpleName()).isInstanceOf(AssertionError.class);
        }
        noSystemTime().check(new ClassFileImporter().importClasses(UsesTheClock.class));
    }

    static class UsesInstantNow { Object f() { return java.time.Instant.now(); } }
    static class UsesLocalDateNowZone { Object f() { return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")); } }
    static class UsesCurrentTimeMillis { long f() { return System.currentTimeMillis(); } }
    static class UsesTheClock { Object f(Clock c) { return java.time.LocalDate.now(c); } }
}
