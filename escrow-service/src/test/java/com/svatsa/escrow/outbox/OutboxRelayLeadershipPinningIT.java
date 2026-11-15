package com.svatsa.escrow.outbox;

import com.svatsa.escrow.test.AbstractIntegrationTest;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for chaos/FINDINGS.md #55: the relay's leader lock must never pin a virtual thread.
 *
 * The relay ticks on a virtual thread. OutboxRelayLeadership used synchronized methods that do JDBC
 * I/O; on JDK 21 (the runtime image) that pins the carrier, and in chaos K2, under load right after a
 * kill -9, the app deadlocked: the pinned relay waited for a pooled connection that only unmounted
 * virtual threads could hand back, with both carriers of the 2-CPU container pinned.
 *
 * This runs the leadership's lock / check / step-down cycle on a virtual thread under a JFR recording
 * of jdk.VirtualThreadPinned (threshold 0) and requires no event with OutboxRelayLeadership on the
 * stack. On JDK 24+ (JEP 491) monitors no longer pin, so the old code passes there; CI and the
 * container run JDK 21, where it fails.
 */
@DisplayName("Outbox relay leadership never pins a virtual thread")
class OutboxRelayLeadershipPinningIT extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("ensureLeadership and close on a virtual thread record no VirtualThreadPinned event")
    void leadershipDoesNotPin() throws Exception {
        List<RecordedEvent> pinned = Collections.synchronizedList(new ArrayList<>());
        try (RecordingStream jfr = new RecordingStream()) {
            jfr.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
            jfr.onEvent("jdk.VirtualThreadPinned", pinned::add);
            jfr.startAsync();

            OutboxRelayLeadership leadership = new OutboxRelayLeadership(dataSource, "pinning-test");
            Thread vt = Thread.ofVirtual().name("pinning-test").start(() -> {
                for (int i = 0; i < 20; i++) {
                    leadership.ensureLeadership();   // borrows a connection, runs pg_try_advisory_lock / isValid
                }
                leadership.close();                  // pg_advisory_unlock when it led
            });
            vt.join(Duration.ofSeconds(60));
            assertThat(vt.isAlive()).isFalse();
            jfr.stop();                              // flushes: every event recorded so far is delivered
        }

        List<String> fromLeadership = pinned.stream()
            .filter(e -> e.getStackTrace() != null && e.getStackTrace().getFrames().stream()
                .map(RecordedFrame::getMethod)
                .anyMatch(m -> m.getType().getName().equals(OutboxRelayLeadership.class.getName())))
            .map(e -> e.getStackTrace().getFrames().stream().limit(12)
                .map(f -> f.getMethod().getType().getName() + "." + f.getMethod().getName())
                .toList().toString())
            .toList();
        assertThat(fromLeadership).as("virtual-thread pinning inside OutboxRelayLeadership (JDK %s)",
            Runtime.version()).isEmpty();
    }
}
