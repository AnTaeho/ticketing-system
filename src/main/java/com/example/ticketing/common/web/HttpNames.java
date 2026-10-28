package com.example.ticketing.common.web;

// 화면 스크립트·가짜 결제사와 맞춰 쓰는 헤더·쿠키 이름
public final class HttpNames {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ID_COOKIE = "bk_uid";
    public static final String PG_SCENARIO_HEADER = "X-Fake-Pg-Scenario";

    private HttpNames() {
    }
}
