package io.tradeflow.ledger.config;

import io.tradeflow.ledger.exception.DuplicateIdempotencyKeyException;
import io.tradeflow.ledger.exception.InvalidOrderEventException;
import io.tradeflow.ledger.exception.ReservationNotFoundException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides what happens when a Kafka message fails to process.
 *
 * <p>Flow: listener throws → {@code kafkaErrorHandler} (retry or give up?)
 * → {@code deadLetterPublishingRecoverer} (where to send it?) → {@code dltKafkaTemplate} (sends it).
 *
 * <p>The rest of the consumer setup (manual ack, JSON, long poll) is in application.yml.
 * Spring Boot plugs the error handler bean below into the listener automatically.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final int ANY_PARTITION = -1;

    /**
     * Packs and sends a failed message to the dead-letter topic (DLT).
     *
     * <p>A failed message arrives in one of two forms, so it needs two serializers:
     * broken bytes (JSON couldn't be read) are sent as-is; a normal {@code OrderEvent}
     * (our code failed on it) is sent as JSON.
     */
    @Bean
    public KafkaTemplate<String, Object> dltKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<Class<?>, Serializer<?>> valueSerializers = new LinkedHashMap<>();
        valueSerializers.put(byte[].class, new ByteArraySerializer());
        valueSerializers.put(Object.class, new JsonSerializer<>());

        DefaultKafkaProducerFactory<String, Object> producerFactory = new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(null),   // null: no SSL bundle configured
                new StringSerializer(),
                new DelegatingByTypeSerializer(valueSerializers, true));
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Decides where a failed message goes: {@code order-events-dlt}, same key ({@code orderId}).
     * Called by the error handler; uses {@code dltKafkaTemplate} to do the actual send.
     *
     * <p>{@code ANY_PARTITION}: let Kafka pick the DLT partition. Otherwise, it copies the original
     * partition number, and the send fails if the DLT has fewer partitions.
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaTemplate<String, Object> dltKafkaTemplate,
            @Value("${app.kafka.topics.order-events-dlt}") String dltTopic) {
        return new DeadLetterPublishingRecoverer(dltKafkaTemplate,
                (record, exception) -> new TopicPartition(dltTopic, ANY_PARTITION));
    }

    /**
     * Decides, for each failure: retry it, or give up and send it to the DLT.
     *
     * <ul>
     *   <li>Temporary error (e.g. DB down): retry 3 times (after 1s, 2s, 4s), then DLT.</li>
     *   <li>Business error (listed below) or unreadable JSON: DLT right away — retrying gives
     *       the same result.</li>
     * </ul>
     *
     * <p>{@code commitRecovered}: once sent to the DLT, mark the message as done, so a restart
     * doesn't process it again and send a second copy.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer deadLetterPublishingRecoverer) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_BACKOFF_MS);
        backOff.setMultiplier(BACKOFF_MULTIPLIER);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterPublishingRecoverer, backOff);
        errorHandler.addNotRetryableExceptions(
                ReservationNotFoundException.class,
                DuplicateIdempotencyKeyException.class,
                InvalidOrderEventException.class);
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }

}
