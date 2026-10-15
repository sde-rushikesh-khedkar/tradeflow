package io.tradeflow.ledger.kafka.event;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * The kinds of order events ledger-service understands.
 *
 * <p>Only {@code ORDER_CANCELLED} needs ledger work (release the reserved money).
 * Any other type order-service sends — today or in future — is read as {@code UNKNOWN}
 * and skipped, instead of failing to parse and landing in the DLT.
 */
public enum OrderEventType {

    /** Order was cancelled: release the money held for it. */
    ORDER_CANCELLED,

    /** Any event type the ledger doesn't act on. */
    @JsonEnumDefaultValue
    UNKNOWN
}
