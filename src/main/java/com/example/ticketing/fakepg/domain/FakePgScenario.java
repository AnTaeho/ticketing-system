package com.example.ticketing.fakepg.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum FakePgScenario {
    SUCCESS,
    DECLINE,
    SLOW_SUCCESS,
    SLOW_DECLINE;

    public static Optional<FakePgScenario> parse(String raw) {
        String name = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(s -> s.name().equals(name)).findFirst();
    }
}
