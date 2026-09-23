package io.tradeflow.ledger.model;

public record AccountBalance(
        String userId,
        long availableCents,
        long reservedCents,
        String currencyCode
) {}
