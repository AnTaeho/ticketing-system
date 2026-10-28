package com.example.ticketing.payment.domain;

public enum PaymentStatus {
    REQUESTED,
    UNKNOWN,
    APPROVED,
    DECLINED,
    CANCELLED;

    // PG 결론을 아직 모른다. 대사가 다시 확인하고, 결론이 날 때까지 좌석을 붙잡아 둔다
    public boolean isOpen() {
        return this == REQUESTED || this == UNKNOWN;
    }
}
