package com.example.ticketing.payment.dto;

import java.time.LocalDateTime;
import java.util.List;

public sealed interface StartResult {

    // 이번 요청이 결제를 만들었다. PG를 불러야 한다
    record Started(
            long paymentId,
            long holdId,
            long performanceId,
            long userId,
            String orderId,
            int amount,
            String holdToken,
            List<Long> seatIds,
            LocalDateTime payDeadline,
            LocalDateTime dbNow
    ) implements StartResult {
    }

    // 같은 요청이 이미 결제를 만들었다. PG를 다시 부르지 않고 현재 결과를 돌려준다
    record Existing(PaymentSettlement settlement) implements StartResult {
    }
}
