package io.tradeflow.ledger.exception;

/**
 * Thrown when a release (or capture) operation references a reservation
 * that either never existed or has already been released/captured.
 *
 * <p><b>Why derive existence from the ledger, not a status flag:</b> there is
 * no {@code status} column on a reservation — outstanding state is always
 * {@code SUM(escrow CREDIT) - SUM(escrow DEBIT)} for the reservation's
 * {@code reference_id}. A zero or negative result means nothing is left to
 * release, whether that is because the key never existed or because a prior
 * release/capture already consumed it. Both cases are indistinguishable from
 * the ledger's point of view and both are caller errors — fail the same way.
 *
 * <p><b>Thread safety:</b> immutable after construction.
 *
 * <p><b>Spring context:</b> none — thrown by LedgerEngine, caught by
 * GlobalExceptionHandler and GrpcExceptionHandler.
 */
public class ReservationNotFoundException extends LedgerException {

    private final String reservationId;

    public ReservationNotFoundException(String reservationId) {
        super("No outstanding reservation found for id: " + reservationId);
        this.reservationId = reservationId;
    }

    public String getReservationId() {
        return this.reservationId;
    }

}
