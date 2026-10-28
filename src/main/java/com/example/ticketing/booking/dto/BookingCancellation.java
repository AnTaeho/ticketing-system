package com.example.ticketing.booking.dto;

import com.example.ticketing.hold.domain.GateHold;
import java.util.Optional;

// 이번 호출이 취소했을 때만 커밋 뒤 지울 좌석 게이트 키가 있다
public record BookingCancellation(long bookingId, RefundStatus refund, Optional<GateHold> releasedGate) {

    public static BookingCancellation cancelled(long bookingId, GateHold releasedGate) {
        return new BookingCancellation(bookingId, RefundStatus.REQUESTED, Optional.of(releasedGate));
    }

    // 이미 취소된 예매다. 그새 남이 잡았을 수 있어 좌석 키는 건드리지 않는다
    public static BookingCancellation alreadyCancelled(long bookingId, RefundStatus refund) {
        return new BookingCancellation(bookingId, refund, Optional.empty());
    }
}
