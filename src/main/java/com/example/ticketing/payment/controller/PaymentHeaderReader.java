package com.example.ticketing.payment.controller;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.web.DemoMode;
import com.example.ticketing.common.web.HttpNames;
import com.example.ticketing.payment.dto.PayCommand;
import com.example.ticketing.payment.dto.PgScenario;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

// 결제 요청 헤더의 형식 검증은 여기서만 한다. 시연용 시나리오 헤더는 prod에서 값이 있어도 무시한다
@Component
public class PaymentHeaderReader {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final boolean demo;

    public PaymentHeaderReader(DemoMode demoMode) {
        this.demo = demoMode.enabled();
    }

    public PayCommand toCommand(long userId, long holdId, String idempotencyKey, String scenarioHeader) {
        return new PayCommand(userId, holdId, idempotencyKey(idempotencyKey), scenario(scenarioHeader));
    }

    private static String idempotencyKey(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String key = header.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    Map.of("header", HttpNames.IDEMPOTENCY_KEY_HEADER, "maxLength", MAX_IDEMPOTENCY_KEY_LENGTH));
        }
        return key;
    }

    private Optional<PgScenario> scenario(String header) {
        if (!demo || header == null || header.isBlank()) {
            return Optional.empty();
        }
        String name = header.trim();
        for (PgScenario scenario : PgScenario.values()) {
            if (scenario.name().equalsIgnoreCase(name)) {
                return Optional.of(scenario);
            }
        }
        throw new ApiException(ErrorCode.INVALID_REQUEST,
                Map.of("header", HttpNames.PG_SCENARIO_HEADER, "value", name));
    }
}
