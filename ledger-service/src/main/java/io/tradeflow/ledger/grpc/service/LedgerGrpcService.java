package io.tradeflow.ledger.grpc.service;

import io.grpc.stub.StreamObserver;
import io.tradeflow.ledger.grpc.exception.LedgerGrpcExceptionHandler;
import io.tradeflow.ledger.model.AccountBalance;
import io.tradeflow.ledger.service.LedgerEngine;
import io.tradeflow.proto.ledger.CaptureFundsRequest;
import io.tradeflow.proto.ledger.CaptureFundsResponse;
import io.tradeflow.proto.ledger.GetBalanceRequest;
import io.tradeflow.proto.ledger.GetBalanceResponse;
import io.tradeflow.proto.ledger.LedgerServiceGrpc;
import io.tradeflow.proto.ledger.ReserveFundsRequest;
import io.tradeflow.proto.ledger.ReserveFundsResponse;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC server implementation of the LedgerService contract defined in ledger.proto.
 *
 * <p>Thin translation layer between the wire contract and {@link LedgerEngine}: unpacks
 * request fields, calls the engine, packs the result. No business logic lives here —
 * that stays in LedgerEngine so REST and gRPC callers share one implementation.
 *
 * <p>Failure handling: engine exceptions are not caught here. They propagate to
 * {@link LedgerGrpcExceptionHandler}, which converts them to a {@code StatusRuntimeException}
 * via {@code responseObserver.onError} — mirrors {@code GlobalExceptionHandler}'s REST
 * {@code ProblemDetail} pattern.
 *
 * <p><b>Thread safety:</b> stateless singleton — safe for concurrent use.
 *
 * <p><b>Spring context:</b> singleton, registered on the internal gRPC (Netty) server,
 * separate from Tomcat.
 */
@GrpcService
public class LedgerGrpcService extends LedgerServiceGrpc.LedgerServiceImplBase {

    private final LedgerEngine ledgerEngine;

    public LedgerGrpcService(LedgerEngine ledgerEngine) {
        this.ledgerEngine = ledgerEngine;
    }

    /**
     * Holds funds in escrow against an order.
     *
     * @param request          user, order, amount, currency, and idempotency key
     * @param responseObserver carries the reservation id back to the caller
     */
    @Override
    public void reserveFunds(ReserveFundsRequest request, StreamObserver<ReserveFundsResponse> responseObserver) {
        String reservationId = ledgerEngine.reserveFunds(
                request.getUserId(),
                request.getCurrency(),
                request.getIdempotencyKey(),
                request.getAmountCents(),
                request.getOrderId()
        );

        ReserveFundsResponse response = ReserveFundsResponse.newBuilder()
                .setSuccess(true)
                .setReservationId(reservationId)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /**
     * Finalizes a reservation, moving its escrowed funds to revenue.
     *
     * @param request          the reservation to finalize
     * @param responseObserver carries the captured amount back to the caller
     */
    @Override
    public void captureFunds(CaptureFundsRequest request, StreamObserver<CaptureFundsResponse> responseObserver) {
        long capturedCents = ledgerEngine.captureFunds(
                request.getUserId(),
                request.getCurrency(),
                request.getIdempotencyKey(),
                request.getReservationId(),
                request.getOrderId()
        );

        CaptureFundsResponse response = CaptureFundsResponse.newBuilder()
                .setSuccess(true)
                .setCapturedCents(capturedCents)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /**
     * Returns a user's available and reserved balance. Read-only.
     *
     * @param request          user and currency to look up
     * @param responseObserver carries the balance back to the caller
     */
    @Override
    public void getBalance(GetBalanceRequest request, StreamObserver<GetBalanceResponse> responseObserver) {
        AccountBalance balance = ledgerEngine.getAccountBalance(request.getUserId(), request.getCurrency());

        GetBalanceResponse response = GetBalanceResponse.newBuilder()
                .setUserId(balance.userId())
                .setAvailableBalanceCents(balance.availableCents())
                .setReservedBalanceCents(balance.reservedCents())
                .setCurrency(balance.currencyCode())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

}
