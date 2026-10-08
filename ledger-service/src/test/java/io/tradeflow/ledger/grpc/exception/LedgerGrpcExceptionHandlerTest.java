package io.tradeflow.ledger.grpc.exception;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.tradeflow.ledger.exception.DuplicateIdempotencyKeyException;
import io.tradeflow.ledger.exception.InsufficientFundsException;
import io.tradeflow.ledger.exception.ReservationNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link LedgerGrpcExceptionHandler} verifying each domain exception
 * maps to the correct gRPC {@link Status} code and description.
 */
@DisplayName("LedgerGrpcExceptionHandler")
class LedgerGrpcExceptionHandlerTest {

    private final LedgerGrpcExceptionHandler handler = new LedgerGrpcExceptionHandler();

    @Nested
    @DisplayName("handleInsufficientFunds()")
    class HandleInsufficientFunds {

        @Test
        @DisplayName("maps to FAILED_PRECONDITION with the exception message")
        void mapsToFailedPrecondition() {
            InsufficientFundsException ex = new InsufficientFundsException("user-abc", 1000L, 500L);

            StatusRuntimeException result = handler.handleInsufficientFunds(ex);

            assertAll(
                    () -> assertEquals(Status.Code.FAILED_PRECONDITION, result.getStatus().getCode()),
                    () -> assertEquals(ex.getMessage(), result.getStatus().getDescription())
            );
        }
    }

    @Nested
    @DisplayName("handleReservationNotFound()")
    class HandleReservationNotFound {

        @Test
        @DisplayName("maps to NOT_FOUND with the exception message")
        void mapsToNotFound() {
            ReservationNotFoundException ex = new ReservationNotFoundException("reservation-1");

            StatusRuntimeException result = handler.handleReservationNotFound(ex);

            assertAll(
                    () -> assertEquals(Status.Code.NOT_FOUND, result.getStatus().getCode()),
                    () -> assertEquals(ex.getMessage(), result.getStatus().getDescription())
            );
        }
    }

    @Nested
    @DisplayName("handleDuplicateIdempotencyKey()")
    class HandleDuplicateIdempotencyKey {

        @Test
        @DisplayName("maps to ALREADY_EXISTS with the exception message")
        void mapsToAlreadyExists() {
            DuplicateIdempotencyKeyException ex = new DuplicateIdempotencyKeyException("key-123");

            StatusRuntimeException result = handler.handleDuplicateIdempotencyKey(ex);

            assertAll(
                    () -> assertEquals(Status.Code.ALREADY_EXISTS, result.getStatus().getCode()),
                    () -> assertEquals(ex.getMessage(), result.getStatus().getDescription())
            );
        }
    }

    @Nested
    @DisplayName("handleAuthentication()")
    class HandleAuthentication {

        @Test
        @DisplayName("maps to UNAUTHENTICATED with the exception message")
        void mapsToUnauthenticated() {
            AuthenticationException ex = new BadCredentialsException("invalid token");

            StatusRuntimeException result = handler.handleAuthentication(ex);

            assertAll(
                    () -> assertEquals(Status.Code.UNAUTHENTICATED, result.getStatus().getCode()),
                    () -> assertEquals(ex.getMessage(), result.getStatus().getDescription())
            );
        }
    }

    @Nested
    @DisplayName("handleAccessDenied()")
    class HandleAccessDenied {

        @Test
        @DisplayName("maps to PERMISSION_DENIED with the exception message")
        void mapsToPermissionDenied() {
            AccessDeniedException ex = new AccessDeniedException("insufficient role");

            StatusRuntimeException result = handler.handleAccessDenied(ex);

            assertAll(
                    () -> assertEquals(Status.Code.PERMISSION_DENIED, result.getStatus().getCode()),
                    () -> assertEquals(ex.getMessage(), result.getStatus().getDescription())
            );
        }
    }
}
