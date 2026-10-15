package io.tradeflow.ledger.kafka.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One message read from the {@code order-events} topic.
 *
 * <p>This is the contract order-service must follow when it publishes (task 2.10).
 * The Kafka key is {@code orderId}, so all events for one order arrive in order.
 *
 * <p>No money amount: releasing a reservation only needs to know which reservation.
 * The ledger works out the amount from its own records.
 *
 * <p>{@code ignoreUnknown}: if order-service adds new fields later, the ledger ignores them
 * instead of failing.
 *
 * @param eventId       unique per event; used as the idempotency key, so a redelivered
 *                      message is processed only once
 * @param eventType     what happened to the order; unknown types become {@code UNKNOWN}
 * @param orderId       the order this event is about
 * @param userId        whose money is affected
 * @param reservationId the hold created by {@code reserveFunds}; which money to release
 * @param currencyCode  ISO 4217 code, e.g. "USD"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEvent(
        String eventId,
        @JsonFormat(with = JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
        OrderEventType eventType,
        String orderId,
        String userId,
        String reservationId,
        String currencyCode) {
}
