package com.example.ticketing.payment.dto;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

// Fake PG에 헤더로 넘기는 시연용 시나리오. 이름이 곧 헤더 값이다
public enum PgScenario {
    SUCCESS,
    DECLINE,
    SLOW_SUCCESS,
    SLOW_DECLINE;

    public static Optional<PgScenario> parse(String raw) {
        String name = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(s -> s.name().equals(name)).findFirst();
    }
}
