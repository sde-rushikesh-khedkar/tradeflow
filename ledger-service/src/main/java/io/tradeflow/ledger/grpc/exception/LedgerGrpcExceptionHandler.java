package io.tradeflow.ledger.grpc.exception;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.tradeflow.ledger.exception.DuplicateIdempotencyKeyException;
import io.tradeflow.ledger.exception.InsufficientFundsException;
import io.tradeflow.ledger.exception.ReservationNotFoundException;
import io.tradeflow.ledger.grpc.service.LedgerGrpcService;
import net.devh.boot.grpc.server.advice.GrpcAdvice;
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

/**
 * Centralised gRPC error handler for the Ledger Service.
 *
 * <p>Mirrors {@code GlobalExceptionHandler}'s REST {@code ProblemDetail} pattern:
 * one place owns the mapping from domain exception to wire-level error, so
 * {@link LedgerGrpcService} stays free of try/catch blocks.
 *
 * <p><b>Thread safety:</b> stateless singleton — safe for concurrent use.
 *
 * <p><b>Spring context:</b> singleton.
 */
@GrpcAdvice
public class LedgerGrpcExceptionHandler {

    /**
     * Maps InsufficientFundsException to FAILED_PRECONDITION.
     *
     * <p>The request was well-formed but the business rule (sufficient balance)
     * was not satisfied — same reasoning as the REST 422 mapping.
     */
    @GrpcExceptionHandler(InsufficientFundsException.class)
    public StatusRuntimeException handleInsufficientFunds(InsufficientFundsException ex) {
        return Status.FAILED_PRECONDITION
                .withDescription(ex.getMessage())
                .asRuntimeException();
    }

    /**
     * Maps ReservationNotFoundException to NOT_FOUND.
     *
     * <p>The reservation identified by the caller either never existed or has
     * already been released/captured — same reasoning as the REST 404 mapping.
     */
    @GrpcExceptionHandler(ReservationNotFoundException.class)
    public StatusRuntimeException handleReservationNotFound(ReservationNotFoundException ex) {
        return Status.NOT_FOUND
                .withDescription(ex.getMessage())
                .asRuntimeException();
    }

    /**
     * Maps DuplicateIdempotencyKeyException to ALREADY_EXISTS.
     *
     * <p>The idempotency key was already processed and its cached response could
     * not be reconstructed — same reasoning as the REST 409 mapping. Callers
     * should not retry.
     */
    @GrpcExceptionHandler(DuplicateIdempotencyKeyException.class)
    public StatusRuntimeException handleDuplicateIdempotencyKey(DuplicateIdempotencyKeyException ex) {
        return Status.ALREADY_EXISTS
                .withDescription(ex.getMessage())
                .asRuntimeException();
    }

    /**
     * Maps AuthenticationException to UNAUTHENTICATED — no/bad/expired token.
     * Reasoned, not yet IT-verified (see task-1.9).
     */
    @GrpcExceptionHandler(AuthenticationException.class)
    public StatusRuntimeException handleAuthentication(AuthenticationException ex) {
        return Status.UNAUTHENTICATED
                .withDescription(ex.getMessage())
                .asRuntimeException();
    }

    /** Maps AccessDeniedException to PERMISSION_DENIED — authenticated but wrong role. */
    @GrpcExceptionHandler(AccessDeniedException.class)
    public StatusRuntimeException handleAccessDenied(AccessDeniedException ex) {
        return Status.PERMISSION_DENIED
                .withDescription(ex.getMessage())
                .asRuntimeException();
    }

}
