package io.tradeflow.ledger.kafka.event;

import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link OrderEvent} — reads JSON exactly the way the consumer does
 * (same deserializer, same settings as application.yml) and checks the result.
 */
@DisplayName("OrderEvent")
class OrderEventTest {

    private static final String TOPIC = "order-events";

    private final JsonDeserializer<OrderEvent> deserializer =
            new JsonDeserializer<>(OrderEvent.class, false);

    @AfterEach
    void tearDown() {
        deserializer.close();
    }

    @Nested
    @DisplayName("JSON deserialization")
    class Deserialization {

        @Test
        @DisplayName("returns all fields when JSON matches the contract")
        void returnsAllFieldsForValidJson() {
            OrderEvent event = read("""
                    {"eventId":"e-003","eventType":"ORDER_CANCELLED","orderId":"order-7f3a",
                     "userId":"user-123","reservationId":"res-91bd","currencyCode":"USD"}
                    """);

            assertAll(
                    () -> assertEquals("e-003", event.eventId()),
                    () -> assertEquals(OrderEventType.ORDER_CANCELLED, event.eventType()),
                    () -> assertEquals("order-7f3a", event.orderId()),
                    () -> assertEquals("user-123", event.userId()),
                    () -> assertEquals("res-91bd", event.reservationId()),
                    () -> assertEquals("USD", event.currencyCode())
            );
        }

        @Test
        @DisplayName("returns UNKNOWN when eventType is one the ledger doesn't know")
        void returnsUnknownForUnrecognisedEventType() {
            OrderEvent event = read("""
                    {"eventId":"e-001","eventType":"ORDER_CREATED","orderId":"order-7f3a"}
                    """);

            assertEquals(OrderEventType.UNKNOWN, event.eventType());
        }

        @Test
        @DisplayName("ignores fields the ledger doesn't know about")
        void ignoresUnknownFields() {
            OrderEvent event = read("""
                    {"eventId":"e-003","eventType":"ORDER_CANCELLED","shippingAddress":"somewhere"}
                    """);

            assertEquals("e-003", event.eventId());
        }

        @Test
        @DisplayName("throws SerializationException when the message is not valid JSON")
        void throwsOnMalformedJson() {
            assertThrows(SerializationException.class, () -> read("{not json"));
        }
    }

    private OrderEvent read(String json) {
        return deserializer.deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8));
    }

}
