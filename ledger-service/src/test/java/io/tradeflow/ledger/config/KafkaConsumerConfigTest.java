package io.tradeflow.ledger.config;

import io.tradeflow.ledger.exception.DuplicateIdempotencyKeyException;
import io.tradeflow.ledger.exception.ReservationNotFoundException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KafkaConsumerConfig} — proves which failures retry, which go
 * straight to the DLT, and where dead-lettered records land.
 */
@DisplayName("KafkaConsumerConfig")
class KafkaConsumerConfigTest {

    private static final String SOURCE_TOPIC = "order-events";
    private static final String DLT_TOPIC = "order-events-dlt";
    private static final String ORDER_KEY = "order-7f3a";

    private final KafkaConsumerConfig config = new KafkaConsumerConfig();
    private final ConsumerRecord<String, Object> record =
            new ConsumerRecord<>(SOURCE_TOPIC, 2, 42L, ORDER_KEY, "payload");

    @Nested
    @DisplayName("kafkaErrorHandler()")
    class KafkaErrorHandlerTests {

        private DeadLetterPublishingRecoverer recoverer;
        private DefaultErrorHandler errorHandler;
        private Consumer<?, ?> consumer;
        private MessageListenerContainer container;

        @BeforeEach
        void setUp() {
            recoverer = mock(DeadLetterPublishingRecoverer.class);
            errorHandler = config.kafkaErrorHandler(recoverer);
            consumer = mock(Consumer.class);
            container = mock(MessageListenerContainer.class);

            ContainerProperties containerProperties = new ContainerProperties(SOURCE_TOPIC);
            containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
            when(container.getContainerProperties()).thenReturn(containerProperties);
            when(container.isRunning()).thenReturn(true);
        }

        @Test
        @DisplayName("dead-letters ReservationNotFoundException immediately without retrying")
        void deadLettersReservationNotFoundWithoutRetry() {
            handle(listenerFailure(new ReservationNotFoundException("res-91bd")));

            verify(recoverer).accept(any(), any(), any());
        }

        @Test
        @DisplayName("dead-letters DuplicateIdempotencyKeyException immediately without retrying")
        void deadLettersDuplicateKeyWithoutRetry() {
            handle(listenerFailure(new DuplicateIdempotencyKeyException("e-001")));

            verify(recoverer).accept(any(), any(), any());
        }

        @Test
        @DisplayName("dead-letters DeserializationException immediately without retrying")
        void deadLettersDeserializationFailureWithoutRetry() {
            DeserializationException poisonPill =
                    new DeserializationException("bad json", new byte[] {1, 2}, false, null);

            handle(poisonPill);

            verify(recoverer).accept(any(), any(), any());
        }

        @Test
        @DisplayName("seeks back to retry a transient DB failure instead of dead-lettering it")
        void retriesTransientFailure() {
            assertThrows(KafkaException.class,
                    () -> handle(listenerFailure(new TransientDataAccessResourceException("db down"))));

            assertAll(
                    () -> verify(consumer).seek(new TopicPartition(SOURCE_TOPIC, 2), 42L),
                    () -> verify(recoverer, never()).accept(any(), any(), any())
            );
        }

        @Test
        @DisplayName("commits the offset of a dead-lettered record so a restart does not redeliver it")
        void commitsOffsetAfterDeadLettering() {
            handle(listenerFailure(new ReservationNotFoundException("res-91bd")));

            verify(consumer).commitSync(
                    eq(Map.of(new TopicPartition(SOURCE_TOPIC, 2), new OffsetAndMetadata(43L))), any());
        }

        /** The path the container takes for a record listener: handleRemaining, not handleOne. */
        private void handle(Exception thrownException) {
            errorHandler.handleRemaining(thrownException, List.of(record), consumer, container);
        }

        private ListenerExecutionFailedException listenerFailure(Exception cause) {
            return new ListenerExecutionFailedException("listener failed", cause);
        }
    }

    @Nested
    @DisplayName("deadLetterPublishingRecoverer()")
    class DeadLetterPublishingRecovererTests {

        @Test
        @DisplayName("publishes to the configured DLT with the original key and no fixed partition")
        @SuppressWarnings("unchecked")
        void publishesToDltWithOriginalKey() {
            KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
            when(template.send(any(ProducerRecord.class)))
                    .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
            DeadLetterPublishingRecoverer recoverer = config.deadLetterPublishingRecoverer(template, DLT_TOPIC);

            recoverer.accept(record, mock(Consumer.class), new ReservationNotFoundException("res-91bd"));

            ArgumentCaptor<ProducerRecord<String, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(template).send(sent.capture());
            assertAll(
                    () -> assertEquals(DLT_TOPIC, sent.getValue().topic()),
                    () -> assertEquals(ORDER_KEY, sent.getValue().key()),
                    () -> assertNull(sent.getValue().partition())
            );
        }
    }

}
