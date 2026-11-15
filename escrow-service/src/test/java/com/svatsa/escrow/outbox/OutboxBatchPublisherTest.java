package com.svatsa.escrow.outbox;

import com.svatsa.escrow.outbox.OutboxRelay.OutboxRow;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay's marking rules (OutboxBatchPublisher), against a real KafkaTemplate over a
 * MockProducer that fails chosen records: asynchronously (the broker says no), synchronously
 * (send throws), or never acknowledges them.
 */
@DisplayName("Outbox batch: ack and per-aggregate ordering")
class OutboxBatchPublisherTest {

    /** Fails or stalls chosen records (by value = event id); everything else is acked at once. */
    static final class SelectiveProducer extends MockProducer<String, String> {
        final Set<String> failAsync = new HashSet<>();
        final Set<String> failSync = new HashSet<>();
        final Set<String> neverAck = new HashSet<>();
        final Set<String> failedBeforeReturn = new HashSet<>();
        final List<String> attempted = new ArrayList<>();   // every record that reached send(), in order

        SelectiveProducer() {
            super(true, new StringSerializer(), new StringSerializer());
        }

        @Override
        public synchronized Future<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
            String id = record.value();
            if (failSync.contains(id)) {
                throw new KafkaException("cannot serialize " + id + " (test)");
            }
            attempted.add(id);
            if (failAsync.contains(id)) {
                var error = new org.apache.kafka.common.errors.TimeoutException("broker timed out on " + id + " (test)");
                // Like the real producer: the failure is reported later, through the callback, from
                // another (I/O) thread. The future returned here must NOT be done when send() returns:
                // KafkaTemplate.doSend checks isDone() right after send() and, if it already failed,
                // throws synchronously. This used to return a future completed on the common pool, so
                // whether the failure looked async or sync depended on which thread won (flaky in CI).
                Thread.ofPlatform().start(() -> callback.onCompletion(null, error));
                return new CompletableFuture<>();   // never completes; only the callback reports
            }
            if (failedBeforeReturn.contains(id)) {
                // The producer already knows the send failed when send() returns (e.g. buffer full).
                var error = new org.apache.kafka.common.errors.RecordTooLargeException(id + " too large (test)");
                callback.onCompletion(null, error);
                return CompletableFuture.failedFuture(error);
            }
            if (neverAck.contains(id)) {
                return new CompletableFuture<>();   // the callback never fires
            }
            return super.send(record, callback);
        }

        @Override
        public void close() {
            // The template may close its producer after use; keep this one usable for the test.
        }

        @Override
        public void close(Duration timeout) {
        }
    }

    private final SelectiveProducer producer = new SelectiveProducer();
    private final KafkaTemplate<String, String> template = new KafkaTemplate<>(new MockProducerFactory<>(() -> producer));
    private long nextId = 1;

    private OutboxRow row(String aggregate) {
        long id = nextId++;
        return new OutboxRow(id, UUID.randomUUID(), "Agg", aggregate, "Event", "topic", "{}", 0);
    }

    private OutboxBatchPublisher.Result publish(List<OutboxRow> rows) {
        return publish(rows, Duration.ofSeconds(5));
    }

    private OutboxBatchPublisher.Result publish(List<OutboxRow> rows, Duration ackTimeout) {
        return OutboxBatchPublisher.publish(rows, r -> template.send(new ProducerRecord<>(
            r.topic(), OutboxBatchPublisher.aggregateKey(r), r.eventId().toString())), ackTimeout);
    }

    private static String id(OutboxRow r) {
        return r.eventId().toString();
    }

    @Test
    @DisplayName("every row acked → every row published, sent in id order per aggregate")
    void allAcked() {
        OutboxRow x1 = row("X"), y1 = row("Y"), x2 = row("X"), y2 = row("Y");
        var result = publish(List.of(x1, y1, x2, y2));

        assertThat(result.published()).containsExactly(x1, y1, x2, y2);
        assertThat(result.failed()).isEmpty();
        assertThat(result.heldBack()).isEmpty();
        assertThat(producer.attempted).containsExactly(id(x1), id(y1), id(x2), id(y2));
    }

    @Test
    @DisplayName("an async failure: the failed row and every later row of its aggregate stay unpublished; other aggregates are unaffected")
    void asyncFailureHoldsBackTheRestOfThatAggregate() {
        OutboxRow x1 = row("X"), x2 = row("X"), y1 = row("Y"), x3 = row("X"), y2 = row("Y");
        producer.failAsync.add(id(x2));

        var result = publish(List.of(x1, x2, y1, x3, y2));

        assertThat(result.published()).containsExactly(x1, y1, y2);   // X's prefix before the failure, all of Y
        assertThat(result.failed()).containsOnlyKeys(x2);
        // x3 was sent and acked, but marking it would put it ahead of x2: it waits for x2's retry.
        assertThat(result.heldBack()).containsExactly(x3);
        assertThat(producer.attempted).contains(id(x3));
    }

    @Test
    @DisplayName("a synchronous failure: later rows of that aggregate are not even sent")
    void syncFailureStopsSendingThatAggregate() {
        OutboxRow x1 = row("X"), x2 = row("X"), y1 = row("Y"), x3 = row("X"), x4 = row("X"), y2 = row("Y");
        producer.failSync.add(id(x2));

        var result = publish(List.of(x1, x2, y1, x3, x4, y2));

        assertThat(result.published()).containsExactly(x1, y1, y2);
        assertThat(result.failed()).containsOnlyKeys(x2);
        assertThat(result.heldBack()).containsExactly(x3, x4);
        assertThat(producer.attempted).doesNotContain(id(x3), id(x4));
    }

    @Test
    @DisplayName("a send whose future has already failed when send() returns counts as synchronous: later rows are not sent")
    void alreadyFailedFutureIsASynchronousFailure() {
        OutboxRow x1 = row("X"), x2 = row("X"), y1 = row("Y"), x3 = row("X");
        producer.failedBeforeReturn.add(id(x2));

        var result = publish(List.of(x1, x2, y1, x3));

        // KafkaTemplate rethrows a failure it can already see, so the publisher stops X right there.
        assertThat(result.published()).containsExactly(x1, y1);
        assertThat(result.failed()).containsOnlyKeys(x2);
        assertThat(result.heldBack()).containsExactly(x3);
        assertThat(producer.attempted).doesNotContain(id(x3));
    }

    @Test
    @DisplayName("no ack before the deadline → not published (a failure), and the aggregate's later rows wait")
    void noAckIsNotPublished() {
        OutboxRow x1 = row("X"), y1 = row("Y"), x2 = row("X");
        producer.neverAck.add(id(x1));

        long start = System.nanoTime();
        var result = publish(List.of(x1, y1, x2), Duration.ofMillis(300));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(result.published()).containsExactly(y1);
        assertThat(result.failed()).containsOnlyKeys(x1);
        assertThat(result.failed().get(x1)).isInstanceOf(TimeoutException.class);
        assertThat(result.heldBack()).containsExactly(x2);
    }

    @Test
    @DisplayName("random failures over 500 rows / 25 aggregates: published rows are exactly each aggregate's acked prefix")
    void publishedIsAlwaysTheAckedPrefixPerAggregate() {
        Random random = new Random(42);
        List<OutboxRow> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            OutboxRow r = row("A" + random.nextInt(25));
            rows.add(r);
            int roll = random.nextInt(100);
            if (roll < 4) producer.failAsync.add(id(r));
            else if (roll < 6) producer.failSync.add(id(r));
            else if (roll < 7) producer.neverAck.add(id(r));
        }

        var result = publish(rows, Duration.ofMillis(300));

        Map<String, Boolean> broken = new HashMap<>();
        List<OutboxRow> expectedPublished = new ArrayList<>();
        for (OutboxRow r : rows) {
            String key = OutboxBatchPublisher.aggregateKey(r);
            boolean bad = producer.failAsync.contains(id(r)) || producer.failSync.contains(id(r)) || producer.neverAck.contains(id(r));
            if (broken.getOrDefault(key, false)) {
                continue;
            }
            if (bad) {
                broken.put(key, true);
            } else {
                expectedPublished.add(r);
            }
        }
        assertThat(result.published()).containsExactlyElementsOf(expectedPublished);
        assertThat(result.published()).allSatisfy(r -> assertThat(producer.history())
            .as("published only if the producer acked it").anyMatch(h -> h.value().equals(id(r))));
        assertThat(result.published().size() + result.failed().size() + result.heldBack().size()).isEqualTo(rows.size());
    }
}
