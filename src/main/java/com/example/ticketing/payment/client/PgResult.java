package com.example.ticketing.payment.client;

// detail은 UNKNOWN일 때 원인(로그·last_error용)
public record PgResult(PgStatus status, String pgTxId, String detail) {

    public static PgResult of(PgStatus status, String pgTxId) {
        return new PgResult(status, pgTxId, null);
    }

    public static PgResult unknown(String detail) {
        return new PgResult(PgStatus.UNKNOWN, null, detail);
    }

    // 예: "PG 취소 UNKNOWN: connect refused". 원인이 없으면 상태까지만 쓴다
    public String describe(String action) {
        return action + " " + status + (detail != null ? ": " + detail : "");
    }
}
