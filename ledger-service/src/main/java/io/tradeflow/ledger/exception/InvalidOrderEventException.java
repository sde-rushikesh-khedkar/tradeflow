package io.tradeflow.ledger.exception;

/**
 * Thrown when an order-events message is valid JSON but is missing a field the ledger needs
 * (e.g. no {@code reservationId} on an {@code ORDER_CANCELLED}).
 *
 * <p>Retrying can't fix a broken message, so the Kafka error handler sends it straight to the DLT.
 *
 * <p><b>Thread safety:</b> immutable after construction.
 *
 * <p><b>Spring context:</b> none — thrown by OrderEventConsumer, handled by the Kafka error handler.
 */
public class InvalidOrderEventException extends LedgerException {

    private final String eventId;

    public InvalidOrderEventException(String eventId, String reason) {
        super("Invalid order event " + eventId + ": " + reason);
        this.eventId = eventId;
    }

    public String getEventId() {
        return this.eventId;
    }

}
