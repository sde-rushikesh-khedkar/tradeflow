package io.tradeflow.ledger.entity;

/**
 * The set of mutating operations LedgerEngine can perform.
 *
 * <p>Stored on {@link IdempotencyKey#getOperation()} so a replayed request can
 * be checked against the operation it originally ran as, not just its key.
 */
public enum LedgerOperation {
    CREDIT_WALLET,
    DEBIT_WALLET,
    RESERVE_FUNDS,
    RELEASE_FUNDS,
    CAPTURE_FUNDS
}
