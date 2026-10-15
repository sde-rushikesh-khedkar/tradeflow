package io.tradeflow.ledger.kafka.consumer;

import io.tradeflow.ledger.exception.InvalidOrderEventException;
import io.tradeflow.ledger.exception.ReservationNotFoundException;
import io.tradeflow.ledger.kafka.event.OrderEvent;
import io.tradeflow.ledger.kafka.event.OrderEventType;
import io.tradeflow.ledger.service.LedgerEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link OrderEventConsumer} — checks which events call the engine,
 * and that the message is acked only when processing succeeds.
 */
@DisplayName("OrderEventConsumer")
class OrderEventConsumerTest {

    private static final String EVENT_ID = "e-003";
    private static final String ORDER_ID = "order-7f3a";
    private static final String USER_ID = "user-123";
    private static final String RESERVATION_ID = "res-91bd";
    private static final String CURRENCY = "USD";

    private LedgerEngine ledgerEngine;
    private Acknowledgment ack;
    private OrderEventConsumer consumer;

    @BeforeEach
    void setUp() {
        ledgerEngine = mock(LedgerEngine.class);
        ack = mock(Acknowledgment.class);
        consumer = new OrderEventConsumer(ledgerEngine);
    }

    @Nested
    @DisplayName("onOrderEvent() — ORDER_CANCELLED")
    class OrderCancelled {

        @Test
        @DisplayName("releases funds using eventId as the idempotency key")
        void releasesFundsWithEventIdAsIdempotencyKey() {
            consumer.onOrderEvent(cancelled(RESERVATION_ID), ack);

            verify(ledgerEngine).releaseFunds(USER_ID, CURRENCY, EVENT_ID, RESERVATION_ID, ORDER_ID);
        }

        @Test
        @DisplayName("acks the message after funds are released")
        void acksAfterSuccess() {
            consumer.onOrderEvent(cancelled(RESERVATION_ID), ack);

            verify(ack).acknowledge();
        }

        @Test
        @DisplayName("does not ack when the engine throws, so the error handler takes over")
        void doesNotAckWhenEngineThrows() {
            doThrow(new ReservationNotFoundException(RESERVATION_ID))
                    .when(ledgerEngine).releaseFunds(any(), any(), any(), any(), any());

            assertThrows(ReservationNotFoundException.class,
                    () -> consumer.onOrderEvent(cancelled(RESERVATION_ID), ack));

            verify(ack, never()).acknowledge();
        }

        @Test
        @DisplayName("throws InvalidOrderEventException when reservationId is missing, without calling the engine")
        void throwsWhenReservationIdMissing() {
            assertThrows(InvalidOrderEventException.class,
                    () -> consumer.onOrderEvent(cancelled(null), ack));

            verifyNoInteractions(ledgerEngine, ack);
        }

        @Test
        @DisplayName("throws InvalidOrderEventException when reservationId is blank")
        void throwsWhenReservationIdBlank() {
            assertThrows(InvalidOrderEventException.class,
                    () -> consumer.onOrderEvent(cancelled("  "), ack));

            verifyNoInteractions(ledgerEngine, ack);
        }
    }

    @Nested
    @DisplayName("onOrderEvent() — other event types")
    class OtherEventTypes {

        @Test
        @DisplayName("skips UNKNOWN events without calling the engine, and acks them")
        void skipsAndAcksUnknownEvents() {
            OrderEvent event = new OrderEvent(EVENT_ID, OrderEventType.UNKNOWN, ORDER_ID, null, null, null);

            consumer.onOrderEvent(event, ack);

            verifyNoInteractions(ledgerEngine);
            verify(ack).acknowledge();
        }

        @Test
        @DisplayName("throws InvalidOrderEventException when eventType is missing")
        void throwsWhenEventTypeMissing() {
            OrderEvent event = new OrderEvent(EVENT_ID, null, ORDER_ID, USER_ID, RESERVATION_ID, CURRENCY);

            assertThrows(InvalidOrderEventException.class, () -> consumer.onOrderEvent(event, ack));

            verifyNoInteractions(ledgerEngine, ack);
        }
    }

    private OrderEvent cancelled(String reservationId) {
        return new OrderEvent(EVENT_ID, OrderEventType.ORDER_CANCELLED, ORDER_ID, USER_ID, reservationId, CURRENCY);
    }

}
