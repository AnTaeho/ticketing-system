package com.example.ticketing.payment.client;

import java.util.Optional;

// 실패를 예외로 던지지 않고 결과로 돌려준다. 모르면 UNKNOWN
public interface PgClient {

    PgResult approve(String orderId, int amount, Optional<PgScenario> scenario);

    PgResult query(String orderId);

    PgResult cancel(String orderId);
}
