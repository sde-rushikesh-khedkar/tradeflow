package io.tradeflow.ledger.kafka.consumer;

import io.tradeflow.ledger.exception.InvalidOrderEventException;
import io.tradeflow.ledger.kafka.event.OrderEvent;
import io.tradeflow.ledger.service.LedgerEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Reads messages from the {@code order-events} topic and does the ledger work for them.
 *
 * <p>Today that's one thing: when an order is cancelled, release the money held for it.
 * Every other event type is skipped.
 *
 * <p>No business logic here — it checks the message, calls {@link LedgerEngine}, and acks.
 * Same role as {@code LedgerGrpcService}, just for Kafka instead of gRPC.
 *
 * <p><b>Thread safety:</b> stateless; safe to call from multiple listener threads.
 *
 * <p><b>Spring context:</b> singleton.
 */
@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final LedgerEngine ledgerEngine;

    public OrderEventConsumer(LedgerEngine ledgerEngine) {
        this.ledgerEngine = ledgerEngine;
    }

    /**
     * Handles one order event, then marks it as done in Kafka.
     *
     * <p><b>Why ack last:</b> the message is marked done only after the database work has
     * committed. If anything throws, there's no ack — the error handler retries it or sends
     * it to the DLT. A redelivered message is safe: {@code eventId} is the idempotency key,
     * so the engine skips work it has already done.
     *
     * @param event the message, already converted from JSON
     * @param ack   used to mark the message as done; called only on success
     * @throws InvalidOrderEventException if a field the ledger needs is missing
     */
    // Spring calls this once per message on the topic named in application.yml.
    @KafkaListener(topics = "${app.kafka.topics.order-events}")
    public void onOrderEvent(OrderEvent event, Acknowledgment ack) {
        // Without a type we can't tell what to do — send it to the DLT.
        if (event.eventType() == null) {
            throw new InvalidOrderEventException(event.eventId(), "eventType is missing");
        }

        switch (event.eventType()) {
            // Order cancelled: give the held money back to the user.
            case ORDER_CANCELLED -> releaseFundsForCancelledOrder(event);
            // Created / reserved / confirmed etc.: nothing for the ledger to do.
            case UNKNOWN -> log.debug("Skipping order event {} for order {}: no ledger action for this type",
                    event.eventId(), event.orderId());
        }

        // Mark the message done in Kafka. Only reached if nothing above threw.
        ack.acknowledge();
    }

    /** Releases the money reserved for a cancelled order back to the user. */
    private void releaseFundsForCancelledOrder(OrderEvent event) {
        // Check everything releaseFunds needs before touching the database.
        requireField(event, event.eventId(), "eventId");
        requireField(event, event.orderId(), "orderId");
        requireField(event, event.userId(), "userId");
        requireField(event, event.reservationId(), "reservationId");
        requireField(event, event.currencyCode(), "currencyCode");

        // eventId is the idempotency key: if this message is delivered again,
        // the engine sees the key already exists and does nothing.
        ledgerEngine.releaseFunds(
                event.userId(),
                event.currencyCode(),
                event.eventId(),
                event.reservationId(),
                event.orderId());

        log.info("Released funds for cancelled order {} (reservation {}, event {})",
                event.orderId(), event.reservationId(), event.eventId());
    }

    /** Throws if a required field is null or blank. */
    private void requireField(OrderEvent event, String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new InvalidOrderEventException(event.eventId(), fieldName + " is missing");
        }
    }

}
