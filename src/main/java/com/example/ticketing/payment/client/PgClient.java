package com.example.ticketing.payment.client;

import java.util.Optional;

// 실패도 결과 값으로 돌려주고, 모르면 UNKNOWN이다. scenario는 시연용 헤더 값 그대로다
public interface PgClient {

    PgResult approve(String orderId, int amount, Optional<String> scenario);

    PgResult query(String orderId);

    PgResult cancel(String orderId);
}
