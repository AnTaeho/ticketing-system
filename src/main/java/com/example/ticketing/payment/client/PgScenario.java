package com.example.ticketing.payment.client;

// Fake PG에 헤더로 넘기는 시연용 시나리오. 이름이 곧 헤더 값이다
public enum PgScenario {
    SUCCESS,
    DECLINE,
    SLOW_SUCCESS,
    SLOW_DECLINE
}
