package com.example.ticketing.payment.dto;

import com.example.ticketing.hold.domain.GateHold;
import java.time.Instant;
import java.util.Optional;

// 결제 트랜잭션 결과. 이번 호출이 좌석을 확정하거나 풀었을 때만 커밋 뒤 게이트에 반영할 것이 있다
public record PaymentSettlement(PaymentResult result, Optional<GateReflection> gateReflection) {

    public static PaymentSettlement seatsChanged(PaymentResult result, GateHold gate, Instant gateCloseAt) {
        return new PaymentSettlement(result, Optional.of(new GateReflection(gate, gateCloseAt)));
    }

    // 결제가 아직 열려 있거나 다른 호출이 먼저 결론을 냈다. 반영은 좌석을 바꾼 쪽 몫이다
    public static PaymentSettlement seatsUnchanged(PaymentResult result) {
        return new PaymentSettlement(result, Optional.empty());
    }

    public boolean changedSeats() {
        return gateReflection.isPresent();
    }

    // 반영할 키를 호출자가 아는 더 넓은 좌석 목록으로 바꾼다. 토큰이 같은 키만 건드리므로 넉넉해도 안전하다
    public PaymentSettlement withGate(GateHold gate) {
        return new PaymentSettlement(result, gateReflection.map(r -> new GateReflection(gate, r.gateCloseAt())));
    }
}
