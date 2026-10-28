package com.example.ticketing.payment.dto;

import com.example.ticketing.hold.domain.GateHold;
import java.time.LocalDateTime;

public sealed interface PaymentStart {

    // 같은 hold의 결제를 찾았을 때 키가 같으면 그 결과를 돌려주고, 다르면 서비스가 오류를 고르게 넘긴다
    static PaymentStart onSameHold(boolean sameKey, PaymentResult result) {
        return sameKey ? new Existing(result) : new OtherKey(result);
    }

    // 이번 요청이 결제를 만들었다. PG를 불러야 한다. gate는 이 hold가 쥔 좌석의 게이트 키다
    record Started(
            long paymentId,
            String orderId,
            int amount,
            GateHold gate,
            LocalDateTime payDeadline,
            LocalDateTime dbNow
    ) implements PaymentStart {
    }

    // 같은 요청이 이미 결제를 만들었다. PG를 다시 부르지 않고 지금 결과를 돌려준다
    record Existing(PaymentResult result) implements PaymentStart {
    }

    // 이 hold에 다른 멱등키의 결제가 있다
    record OtherKey(PaymentResult other) implements PaymentStart {
    }
}
