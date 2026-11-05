package com.example.ticketing.payment.dto;

import com.example.ticketing.hold.domain.GateHold;
import java.util.Optional;

// 결제 트랜잭션 결과. 이번 호출이 좌석을 확정하거나 풀었을 때만 커밋 뒤 게이트에 반영할 키가 있다
public record PaymentSettlement(PaymentResult result, Optional<GateHold> changedGate) {

    public static PaymentSettlement seatsChanged(PaymentResult result, GateHold gate) {
        return new PaymentSettlement(result, Optional.of(gate));
    }

    // 결제가 아직 열려 있거나 다른 호출이 먼저 결론을 냈다. 반영은 좌석을 바꾼 쪽 몫이다
    public static PaymentSettlement seatsUnchanged(PaymentResult result) {
        return new PaymentSettlement(result, Optional.empty());
    }

    public boolean changedSeats() {
        return changedGate.isPresent();
    }
}
