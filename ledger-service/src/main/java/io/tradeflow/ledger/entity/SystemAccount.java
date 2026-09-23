package io.tradeflow.ledger.entity;

public enum SystemAccount {

    SYSTEM_FUNDING("SYSTEM_FUNDING"),

    SYSTEM_ESCROW("SYSTEM_ESCROW"),

    SYSTEM_REVENUE("SYSTEM_REVENUE");

    private final String userId;

    SystemAccount(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return this.userId;
    }
}
