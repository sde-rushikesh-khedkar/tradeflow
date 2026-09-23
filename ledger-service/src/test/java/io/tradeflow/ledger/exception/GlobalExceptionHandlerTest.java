package io.tradeflow.ledger.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GlobalExceptionHandler} verifying each domain exception
 * maps to the correct RFC 7807 {@link ProblemDetail} status and properties.
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Nested
    @DisplayName("handleInsufficientFunds()")
    class HandleInsufficientFunds {

        @Test
        @DisplayName("maps to 422 with userId, requestedCents, and availableCents")
        void mapsTo422WithFields() {
            InsufficientFundsException ex = new InsufficientFundsException("user-abc", 1000L, 500L);

            ProblemDetail pd = handler.handleInsufficientFunds(ex);

            assertAll(
                    () -> assertEquals(HttpStatus.UNPROCESSABLE_ENTITY.value(), pd.getStatus()),
                    () -> assertEquals("Insufficient Funds", pd.getTitle()),
                    () -> assertEquals("user-abc", pd.getProperties().get("userId")),
                    () -> assertEquals(1000L, pd.getProperties().get("requestedCents")),
                    () -> assertEquals(500L, pd.getProperties().get("availableCents"))
            );
        }
    }

    @Nested
    @DisplayName("handleAccountNotFound()")
    class HandleAccountNotFound {

        @Test
        @DisplayName("maps to 404 with userId")
        void mapsTo404WithUserId() {
            AccountNotFoundException ex = new AccountNotFoundException("user-abc");

            ProblemDetail pd = handler.handleAccountNotFound(ex);

            assertAll(
                    () -> assertEquals(HttpStatus.NOT_FOUND.value(), pd.getStatus()),
                    () -> assertEquals("Account Not Found", pd.getTitle()),
                    () -> assertEquals("user-abc", pd.getProperties().get("userId")),
                    () -> assertEquals("user-abc", ex.getUserId()),
                    () -> assertTrue(ex.getMessage().contains("user-abc"))
            );
        }
    }

    @Nested
    @DisplayName("handleDuplicateIdempotencyKey()")
    class HandleDuplicateIdempotencyKey {

        @Test
        @DisplayName("maps to 409 with idempotencyKey")
        void mapsTo409WithIdempotencyKey() {
            DuplicateIdempotencyKeyException ex = new DuplicateIdempotencyKeyException("key-123");

            ProblemDetail pd = handler.handleDuplicateIdempotencyKey(ex);

            assertAll(
                    () -> assertEquals(HttpStatus.CONFLICT.value(), pd.getStatus()),
                    () -> assertEquals("Duplicate Request", pd.getTitle()),
                    () -> assertEquals("key-123", pd.getProperties().get("idempotencyKey"))
            );
        }
    }

    @Nested
    @DisplayName("handleReservationNotFound()")
    class HandleReservationNotFound {

        @Test
        @DisplayName("maps to 404 with reservationId")
        void mapsTo404WithReservationId() {
            ReservationNotFoundException ex = new ReservationNotFoundException("reserve-123");

            ProblemDetail pd = handler.handleReservationNotFound(ex);

            assertAll(
                    () -> assertEquals(HttpStatus.NOT_FOUND.value(), pd.getStatus()),
                    () -> assertEquals("Reservation Not Found", pd.getTitle()),
                    () -> assertEquals("reserve-123", pd.getProperties().get("reservationId"))
            );
        }
    }

    @Nested
    @DisplayName("handleValidation()")
    class HandleValidation {

        @Test
        @DisplayName("maps to 400 with every field error collected into one list")
        void mapsTo400WithAllFieldErrors() throws NoSuchMethodException {
            // MethodParameter can't be mocked on this JDK (Mockito/Byte Buddy limitation,
            // same as noted in LedgerEngineTest) — build a real one via reflection instead.
            // Which method/parameter it points to is irrelevant; the handler never inspects it.
            MethodParameter parameter = new MethodParameter(Object.class.getMethod("toString"), -1);
            BindingResult bindingResult = mock(BindingResult.class);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(
                    new FieldError("request", "amountCents", "must be positive"),
                    new FieldError("request", "userId", "must not be blank")
            ));
            MethodArgumentNotValidException ex =
                    new MethodArgumentNotValidException(parameter, bindingResult);

            ProblemDetail pd = handler.handleValidation(ex);

            assertAll(
                    () -> assertEquals(HttpStatus.BAD_REQUEST.value(), pd.getStatus()),
                    () -> assertEquals("Invalid Request", pd.getTitle()),
                    () -> assertEquals(
                            List.of("amountCents: must be positive", "userId: must not be blank"),
                            pd.getProperties().get("errors")
                    )
            );
        }
    }
}
