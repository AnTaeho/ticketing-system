package com.example.ticketing.payment.client;

// detail은 UNKNOWN일 때 원인(로그·last_error용)
public record PgResult(PgStatus status, String pgTxId, String detail) {

    public static PgResult of(PgStatus status, String pgTxId) {
        return new PgResult(status, pgTxId, null);
    }

    public static PgResult unknown(String detail) {
        return new PgResult(PgStatus.UNKNOWN, null, detail);
    }
}
