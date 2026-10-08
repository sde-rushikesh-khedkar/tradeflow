package io.tradeflow.ledger.grpc.service;

import io.grpc.stub.StreamObserver;
import io.tradeflow.ledger.exception.InsufficientFundsException;
import io.tradeflow.ledger.exception.ReservationNotFoundException;
import io.tradeflow.ledger.grpc.exception.LedgerGrpcExceptionHandler;
import io.tradeflow.ledger.model.AccountBalance;
import io.tradeflow.ledger.service.LedgerEngine;
import io.tradeflow.proto.ledger.CaptureFundsRequest;
import io.tradeflow.proto.ledger.CaptureFundsResponse;
import io.tradeflow.proto.ledger.GetBalanceRequest;
import io.tradeflow.proto.ledger.GetBalanceResponse;
import io.tradeflow.proto.ledger.ReserveFundsRequest;
import io.tradeflow.proto.ledger.ReserveFundsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link LedgerGrpcService} verifying request fields are unpacked
 * correctly, {@link LedgerEngine} is called with the right arguments, and the
 * response is packed and pushed through the {@link StreamObserver}.
 *
 * <p>{@code LedgerEngine} is a concrete class, not an interface — Mockito's inline
 * mock maker cannot instrument it on this JDK (same Byte Buddy limitation noted in
 * {@code LedgerEngineTest}). Each test subclasses {@code LedgerEngine} directly and
 * overrides only the one method under test, passing null collaborators the override
 * never touches — plain Java subclassing, not a runtime proxy, so it isn't affected.
 *
 * <p>Exception propagation is also verified — this class must not catch engine
 * exceptions itself; {@link LedgerGrpcExceptionHandler} owns that mapping.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerGrpcService")
class LedgerGrpcServiceTest {

    @Mock
    private StreamObserver<ReserveFundsResponse> reserveFundsObserver;

    @Mock
    private StreamObserver<CaptureFundsResponse> captureFundsObserver;

    @Mock
    private StreamObserver<GetBalanceResponse> getBalanceObserver;

    @Nested
    @DisplayName("reserveFunds()")
    class ReserveFunds {

        @Test
        @DisplayName("unpacks request fields, calls LedgerEngine, and returns the reservation id")
        void callsEngineAndReturnsReservationId() {
            ReserveFundsRequest request = ReserveFundsRequest.newBuilder()
                    .setIdempotencyKey("key-1")
                    .setUserId("user-abc")
                    .setOrderId("order-1")
                    .setAmountCents(1000L)
                    .setCurrency("USD")
                    .build();
            LedgerEngine ledgerEngine = new LedgerEngine(null, null, null, null) {
                @Override
                public String reserveFunds(String userId, String currencyCode, String idempotencyKey,
                                            long amountCents, String orderId) {
                    assertAll(
                            () -> assertEquals("user-abc", userId),
                            () -> assertEquals("USD", currencyCode),
                            () -> assertEquals("key-1", idempotencyKey),
                            () -> assertEquals(1000L, amountCents),
                            () -> assertEquals("order-1", orderId)
                    );
                    return "reservation-1";
                }
            };
            LedgerGrpcService grpcService = new LedgerGrpcService(ledgerEngine);

            grpcService.reserveFunds(request, reserveFundsObserver);

            ArgumentCaptor<ReserveFundsResponse> captor = ArgumentCaptor.forClass(ReserveFundsResponse.class);
            verify(reserveFundsObserver).onNext(captor.capture());
            verify(reserveFundsObserver).onCompleted();
            verify(reserveFundsObserver, never()).onError(any());
            assertAll(
                    () -> assertTrue(captor.getValue().getSuccess()),
                    () -> assertEquals("reservation-1", captor.getValue().getReservationId())
            );
        }

        @Test
        @DisplayName("propagates InsufficientFundsException without catching it")
        void propagatesInsufficientFundsException() {
            ReserveFundsRequest request = ReserveFundsRequest.newBuilder()
                    .setIdempotencyKey("key-1")
                    .setUserId("user-abc")
                    .setOrderId("order-1")
                    .setAmountCents(1000L)
                    .setCurrency("USD")
                    .build();
            LedgerEngine ledgerEngine = new LedgerEngine(null, null, null, null) {
                @Override
                public String reserveFunds(String userId, String currencyCode, String idempotencyKey,
                                            long amountCents, String orderId) {
                    throw new InsufficientFundsException("user-abc", 1000L, 500L);
                }
            };
            LedgerGrpcService grpcService = new LedgerGrpcService(ledgerEngine);

            assertThrows(InsufficientFundsException.class,
                    () -> grpcService.reserveFunds(request, reserveFundsObserver));

            verifyNoInteractions(reserveFundsObserver);
        }
    }

    @Nested
    @DisplayName("captureFunds()")
    class CaptureFunds {

        @Test
        @DisplayName("unpacks request fields, calls LedgerEngine, and returns the captured amount")
        void callsEngineAndReturnsCapturedCents() {
            CaptureFundsRequest request = CaptureFundsRequest.newBuilder()
                    .setIdempotencyKey("key-2")
                    .setUserId("user-abc")
                    .setOrderId("order-1")
                    .setReservationId("reservation-1")
                    .setCurrency("USD")
                    .build();
            LedgerEngine ledgerEngine = new LedgerEngine(null, null, null, null) {
                @Override
                public long captureFunds(String userId, String currencyCode, String captureIdempotencyKey,
                                          String originalReservationId, String orderId) {
                    assertAll(
                            () -> assertEquals("user-abc", userId),
                            () -> assertEquals("USD", currencyCode),
                            () -> assertEquals("key-2", captureIdempotencyKey),
                            () -> assertEquals("reservation-1", originalReservationId),
                            () -> assertEquals("order-1", orderId)
                    );
                    return 1000L;
                }
            };
            LedgerGrpcService grpcService = new LedgerGrpcService(ledgerEngine);

            grpcService.captureFunds(request, captureFundsObserver);

            ArgumentCaptor<CaptureFundsResponse> captor = ArgumentCaptor.forClass(CaptureFundsResponse.class);
            verify(captureFundsObserver).onNext(captor.capture());
            verify(captureFundsObserver).onCompleted();
            verify(captureFundsObserver, never()).onError(any());
            assertAll(
                    () -> assertTrue(captor.getValue().getSuccess()),
                    () -> assertEquals(1000L, captor.getValue().getCapturedCents())
            );
        }

        @Test
        @DisplayName("propagates ReservationNotFoundException without catching it")
        void propagatesReservationNotFoundException() {
            CaptureFundsRequest request = CaptureFundsRequest.newBuilder()
                    .setIdempotencyKey("key-2")
                    .setUserId("user-abc")
                    .setOrderId("order-1")
                    .setReservationId("reservation-1")
                    .setCurrency("USD")
                    .build();
            LedgerEngine ledgerEngine = new LedgerEngine(null, null, null, null) {
                @Override
                public long captureFunds(String userId, String currencyCode, String captureIdempotencyKey,
                                          String originalReservationId, String orderId) {
                    throw new ReservationNotFoundException("reservation-1");
                }
            };
            LedgerGrpcService grpcService = new LedgerGrpcService(ledgerEngine);

            assertThrows(ReservationNotFoundException.class,
                    () -> grpcService.captureFunds(request, captureFundsObserver));

            verifyNoInteractions(captureFundsObserver);
        }
    }

    @Nested
    @DisplayName("getBalance()")
    class GetBalance {

        @Test
        @DisplayName("unpacks request fields, calls LedgerEngine, and returns available and reserved balance")
        void callsEngineAndReturnsBalance() {
            GetBalanceRequest request = GetBalanceRequest.newBuilder()
                    .setUserId("user-abc")
                    .setCurrency("USD")
                    .build();
            LedgerEngine ledgerEngine = new LedgerEngine(null, null, null, null) {
                @Override
                public AccountBalance getAccountBalance(String userId, String currencyCode) {
                    assertAll(
                            () -> assertEquals("user-abc", userId),
                            () -> assertEquals("USD", currencyCode)
                    );
                    return new AccountBalance("user-abc", 5000L, 1000L, "USD");
                }
            };
            LedgerGrpcService grpcService = new LedgerGrpcService(ledgerEngine);

            grpcService.getBalance(request, getBalanceObserver);

            ArgumentCaptor<GetBalanceResponse> captor = ArgumentCaptor.forClass(GetBalanceResponse.class);
            verify(getBalanceObserver).onNext(captor.capture());
            verify(getBalanceObserver).onCompleted();
            verify(getBalanceObserver, never()).onError(any());
            assertAll(
                    () -> assertEquals("user-abc", captor.getValue().getUserId()),
                    () -> assertEquals(5000L, captor.getValue().getAvailableBalanceCents()),
                    () -> assertEquals(1000L, captor.getValue().getReservedBalanceCents()),
                    () -> assertEquals("USD", captor.getValue().getCurrency())
            );
        }
    }
}
