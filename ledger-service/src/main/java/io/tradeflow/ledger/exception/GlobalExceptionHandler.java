package io.tradeflow.ledger.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralised REST error handler for the Ledger Service.
 *
 * <p>All exceptions thrown from controllers and services are caught here and
 * mapped to RFC 7807 {@link ProblemDetail} responses. No controller handles
 * exceptions directly — this single class owns the entire error contract.
 *
 * <p><b>Why RFC 7807 ProblemDetail:</b> a standard error shape means every
 * client (order-service, gateway, frontend) parses one structure regardless
 * of which endpoint failed. Custom error JSON requires every client to handle
 * a different shape per service — high coupling, high maintenance cost.
 *
 * <p><b>Why @RestControllerAdvice not @ControllerAdvice:</b> @RestControllerAdvice
 * combines @ControllerAdvice with @ResponseBody — every handler method's return
 * value is serialised to JSON automatically. Without @ResponseBody, Spring would
 * attempt view resolution on ProblemDetail objects.
 *
 * <p><b>Thread safety:</b> stateless singleton — safe for concurrent use.
 *
 * <p><b>Spring context:</b> singleton.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Maps InsufficientFundsException to 422 Unprocessable Entity.
     *
     * <p>422 is correct here — the request was well-formed (valid JSON, valid JWT)
     * but the business rule (sufficient balance) was not satisfied.
     * 400 Bad Request would be wrong — there is nothing wrong with the request itself.
     */
    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
        ProblemDetail pd = ProblemDetail
                .forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setTitle("Insufficient Funds");
        pd.setProperty("userId", ex.getUserId());
        pd.setProperty("requestedCents", ex.getRequestedCents());
        pd.setProperty("availableCents", ex.getAvailableCents());
        return pd;
    }

    /**
     * Maps AccountNotFoundException to 404 Not Found.
     */
    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(AccountNotFoundException ex) {
        ProblemDetail pd = ProblemDetail
                .forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Account Not Found");
        pd.setProperty("userId", ex.getUserId());
        return pd;
    }

    /**
     * Maps DuplicateIdempotencyKeyException to 409 Conflict.
     *
     * <p>409 signals the request conflicts with existing state — a previously
     * processed idempotency key. Clients should not retry on 409.
     */
    @ExceptionHandler(DuplicateIdempotencyKeyException.class)
    public ProblemDetail handleDuplicateIdempotencyKey(DuplicateIdempotencyKeyException ex) {
        ProblemDetail pd = ProblemDetail
                .forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Duplicate Request");
        pd.setProperty("idempotencyKey", ex.getIdempotencyKey());
        return pd;
    }

    /**
     * Maps ReservationNotFoundException to 404 Not Found.
     *
     * <p>404 is correct here — the reservation identified by the client either
     * never existed or has already been released/captured. Nothing left to act on.
     */
    @ExceptionHandler(ReservationNotFoundException.class)
    public ProblemDetail handleReservationNotFound(ReservationNotFoundException ex) {
        ProblemDetail pd = ProblemDetail
                .forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Reservation Not Found");
        pd.setProperty("reservationId", ex.getReservationId());
        return pd;
    }

    /**
     * Maps Bean Validation failures to 400 Bad Request.
     *
     * <p>Collects all field errors into a list so the client receives every
     * validation failure in one response — not just the first one encountered.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail
                .forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        pd.setTitle("Invalid Request");
        pd.setProperty("errors", ex.getBindingResult().getFieldErrors()
                .stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList());
        return pd;
    }

}
