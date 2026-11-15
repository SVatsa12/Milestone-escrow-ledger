package com.svatsa.escrow.common;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGENTS.md rules that a grep found broken once (time from the Clock, rule 11 on Kafka), enforced on escrow-service's main classes so they
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

    /**
     * AGENTS.md rule 11: domain events reach Kafka only through the outbox relay. The consumer
     * dead-letter recoverer is the one documented exception (it re-publishes poison records to .DLT).
     */
    private static com.tngtech.archunit.lang.ArchRule onlyRelayAndDeadLetterTouchKafkaProducers() {
        return noClasses()
            .that().doNotHaveFullyQualifiedName(com.svatsa.escrow.outbox.OutboxRelay.class.getName())
            .and().doNotHaveFullyQualifiedName(com.svatsa.escrow.consumers.KafkaConsumerConfig.class.getName())
            .should().dependOnClassesThat().areAssignableTo(org.springframework.kafka.core.KafkaOperations.class)
            .orShould().dependOnClassesThat().areAssignableTo(org.apache.kafka.clients.producer.Producer.class)
            .because("AGENTS.md rule 11: nothing calls KafkaTemplate except the outbox relay and the dead-letter recoverer");
    }

    @Test
    @DisplayName("only the outbox relay and the dead-letter recoverer use KafkaTemplate")
    void onlyTheRelayAndTheDeadLetterRecovererUseKafkaTemplate() {
        onlyRelayAndDeadLetterTouchKafkaProducers().check(MAIN);
    }

    @Test
    @DisplayName("the KafkaTemplate rule catches any other class")
    void kafkaRuleCatchesOtherClasses() {
        assertThatThrownBy(() -> onlyRelayAndDeadLetterTouchKafkaProducers()
                .check(new ClassFileImporter().importClasses(SendsDirectly.class)))
            .isInstanceOf(AssertionError.class);
    }

    /**
     * No synchronized methods in main code. Scheduled jobs and HTTP requests run on virtual threads,
     * and on JDK 21 (the runtime image) blocking inside a monitor pins the carrier; a 2-CPU container
     * has 2 carriers. FINDINGS #45 (Kafka client) and #55 (the relay's leader lock) both froze the app
     * this way. Use java.util.concurrent locks. (Synchronized BLOCKS are invisible to ArchUnit;
     * OutboxRelayLeadershipPinningIT checks the relay with JFR.)
     */
    private static com.tngtech.archunit.lang.ArchRule noSynchronizedMethods() {
        return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods()
            .that().areDeclaredInClassesThat().resideInAPackage("com.svatsa.escrow..")
            .should().haveModifier(com.tngtech.archunit.core.domain.JavaModifier.SYNCHRONIZED)
            .because("virtual threads pin their carrier while blocked in a monitor on JDK 21 (FINDINGS #45, #55)");
    }

    @Test
    @DisplayName("no synchronized methods in main code (virtual-thread pinning)")
    void noSynchronizedMethodsInMain() {
        noSynchronizedMethods().allowEmptyShould(true).check(MAIN);
    }

    @Test
    @DisplayName("the synchronized rule catches a synchronized method")
    void synchronizedRuleCatchesViolations() {
        assertThatThrownBy(() -> noSynchronizedMethods().check(new ClassFileImporter().importClasses(LocksAMonitor.class)))
            .isInstanceOf(AssertionError.class);
    }

    /** {@code synchronized (lock) { ... }}: ArchUnit sees only method modifiers, so blocks are found in source. */
    private static final java.util.regex.Pattern SYNCHRONIZED_BLOCK = java.util.regex.Pattern.compile("\\bsynchronized\\s*\\(");

    @Test
    @DisplayName("no synchronized blocks in src/main/java (virtual-thread pinning, source scan)")
    void noSynchronizedBlocksInMainSources() throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Path.of("src", "main", "java");   // tests run in the module directory
        org.assertj.core.api.Assertions.assertThat(root).isDirectory();
        List<String> hits = new java.util.ArrayList<>();
        int scanned = 0;
        try (var files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                scanned++;
                hits.addAll(synchronizedBlocks(root.relativize(file).toString(), java.nio.file.Files.readString(file)));
            }
        }
        org.assertj.core.api.Assertions.assertThat(scanned).as("java files scanned").isGreaterThan(50);
        org.assertj.core.api.Assertions.assertThat(hits)
            .as("synchronized blocks pin virtual threads on JDK 21 (FINDINGS #45, #55); use java.util.concurrent locks")
            .isEmpty();
    }

    @Test
    @DisplayName("the source scan finds a synchronized block and ignores comments, strings and text blocks")
    void sourceScanFindsBlocksOnly() {
        String code = """
            class X {
                // synchronized (inALineComment) {}
                /* synchronized (inABlockComment) {} */
                /** synchronized(inJavadoc) */
                String s = "synchronized (inAString)";
                String t = \"""
                    synchronized (inATextBlock)
                    \""";
                char c = '(';
                synchronized void method() { }
                void f() { synchronized (this) { } }
                void g() { synchronized(lock) { } }
            }
            """;
        org.assertj.core.api.Assertions.assertThat(synchronizedBlocks("X.java", code)).containsExactly("X.java:11", "X.java:12");
    }

    /** "file:line" of every synchronized block outside comments, strings, text blocks and char literals. */
    static List<String> synchronizedBlocks(String file, String source) {
        String code = stripCommentsAndLiterals(source);
        List<String> hits = new java.util.ArrayList<>();
        var m = SYNCHRONIZED_BLOCK.matcher(code);
        while (m.find()) {
            long line = code.substring(0, m.start()).chars().filter(ch -> ch == '\n').count() + 1;
            hits.add(file + ":" + line);
        }
        return hits;
    }

    /** Blanks comments and literals (newlines kept, so line numbers stay right). */
    static String stripCommentsAndLiterals(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (s.startsWith("//", i)) {
                while (i < n && s.charAt(i) != '\n') { out.append(' '); i++; }
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(s, i, end, out);
                i = end;
            } else if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                blank(s, i, end, out);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c && s.charAt(j) != '\n') {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                int end = Math.min(n, j + 1);
                blank(s, i, end, out);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void blank(String s, int from, int to, StringBuilder out) {
        for (int k = from; k < to; k++) {
            out.append(s.charAt(k) == '\n' ? '\n' : ' ');
        }
    }

    static class LocksAMonitor {
        synchronized void f() { }
    }

    static class SendsDirectly {
        void f(org.springframework.kafka.core.KafkaTemplate<String, String> t) { t.send("escrow.milestone-events", "x"); }
    }

    static class UsesInstantNow { Object f() { return java.time.Instant.now(); } }
    static class UsesLocalDateNowZone { Object f() { return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")); } }
    static class UsesCurrentTimeMillis { long f() { return System.currentTimeMillis(); } }
    static class UsesTheClock { Object f(Clock c) { return java.time.LocalDate.now(c); } }
}
