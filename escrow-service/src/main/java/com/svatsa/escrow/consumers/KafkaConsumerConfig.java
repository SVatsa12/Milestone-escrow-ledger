package com.svatsa.escrow.consumers;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error handling for the P8 consumers: a record that still fails after two quick retries is
 * published to {@code <topic>.DLT} and the partition moves on, so one poison message can't
 * block the messages behind it.
 *
 * This used to be a bare {@code new DefaultErrorHandler()} with no recoverer: failed records
 * were retried 9 times, logged and dropped, and nothing ever reached a .DLT topic (the javadoc
 * claimed "implicit .DLT support", which Spring Kafka does not have).
 *
 * The dead-letter producer is separate from the relay's String producer: a record that failed
 * deserialization arrives as raw bytes (ErrorHandlingDeserializer), which StringSerializer can't
 * write; records that did deserialize (a handler failed) are written back as JSON.
 */
@Configuration
public class KafkaConsumerConfig {
    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    public DefaultErrorHandler defaultErrorHandler(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new LinkedHashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        Map<Class<?>, org.apache.kafka.common.serialization.Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        byType.put(Object.class, new JsonSerializer<>());
        DefaultKafkaProducerFactory<Object, Object> dltProducer = new DefaultKafkaProducerFactory<>(
            props, null, new DelegatingByTypeSerializer(byType, true));

        // "<topic>.DLT", as P8 and docker-compose's kafka-init name them. Spring Kafka's default
        // is "<topic>-dlt" on the same partition number, a topic nothing creates: the producer
        // looped on UNKNOWN_TOPIC_OR_PARTITION and the poison record went nowhere. Partition -1
        // lets the producer choose (the DLTs have one partition).
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(new KafkaTemplate<>(dltProducer),
            (record, ex) -> new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", -1));
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            log.warn("consumer.dead-letter topic={} partition={} offset={} error={}",
                record.topic(), record.partition(), record.offset(), ex.getMessage());
            recoverer.accept(record, ex);
        }, new FixedBackOff(200L, 2L));
        return handler;
    }

    /**
     * Make sure the dead-letter topics exist (kafka-init creates them in docker compose; tests and
     * other environments may not run it). KafkaAdmin leaves existing topics alone.
     */
    @Bean
    public org.springframework.kafka.core.KafkaAdmin.NewTopics deadLetterTopics() {
        return new org.springframework.kafka.core.KafkaAdmin.NewTopics(
            java.util.stream.Stream.of("escrow.milestone-events", "escrow.payment-events", "ledger.entry-events")
                .map(t -> org.springframework.kafka.config.TopicBuilder.name(t + ".DLT").partitions(1).replicas(1).build())
                .toArray(org.apache.kafka.clients.admin.NewTopic[]::new));
    }
}
