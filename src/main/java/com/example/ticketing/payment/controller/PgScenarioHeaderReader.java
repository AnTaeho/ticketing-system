package com.example.ticketing.payment.controller;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.payment.client.PgScenario;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

// 시연용 시나리오 헤더는 prod가 아닐 때만 읽는다. prod에서는 값이 있어도 무시한다
@Component
public class PgScenarioHeaderReader {

    public static final String HEADER = "X-Fake-Pg-Scenario";

    private final boolean prod;

    public PgScenarioHeaderReader(Environment environment) {
        this.prod = environment.acceptsProfiles(Profiles.of("prod"));
    }

    public Optional<PgScenario> read(String headerValue) {
        if (prod || headerValue == null || headerValue.isBlank()) {
            return Optional.empty();
        }
        String name = headerValue.trim();
        for (PgScenario scenario : PgScenario.values()) {
            if (scenario.name().equalsIgnoreCase(name)) {
                return Optional.of(scenario);
            }
        }
        throw new ApiException(ErrorCode.INVALID_REQUEST, Map.of("header", HEADER, "value", name));
    }
}
