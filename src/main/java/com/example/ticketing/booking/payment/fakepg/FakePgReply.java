package com.example.ticketing.booking.payment.fakepg;

import com.fasterxml.jackson.annotation.JsonInclude;

// PG 응답 흉내라 예매 에러 형식({code, ...})을 쓰지 않는다
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FakePgReply(String orderId, String status, String pgTxId, String reason) {

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    static FakePgReply of(FakePgTransaction tx) {
        return new FakePgReply(tx.getOrderId(), tx.getStatus().name(), tx.getPgTxId(), null);
    }

    static FakePgReply of(String orderId, FakePgStatus status, String pgTxId) {
        return new FakePgReply(orderId, status.name(), pgTxId, null);
    }

    // 취소된 주문에 승인이 다시 오면 돈을 빼지 않고 거절로 답한다
    static FakePgReply toRepeatedApprove(FakePgTransaction tx) {
        if (tx.getStatus() == FakePgStatus.CANCELLED) {
            return new FakePgReply(tx.getOrderId(), FakePgStatus.DECLINED.name(), null, FakePgStatus.CANCELLED.name());
        }
        return of(tx);
    }

    static FakePgReply notFound(String orderId) {
        return new FakePgReply(orderId, NOT_FOUND, null, null);
    }

    static FakePgReply invalid(String orderId, String reason) {
        return new FakePgReply(orderId, INVALID_REQUEST, null, reason);
    }

    public boolean is(FakePgStatus expected) {
        return expected.name().equals(status);
    }

    public boolean isNotFound() {
        return NOT_FOUND.equals(status);
    }
}
