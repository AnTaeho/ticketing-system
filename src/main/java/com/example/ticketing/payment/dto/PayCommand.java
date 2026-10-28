package com.example.ticketing.payment.dto;

import java.util.Optional;

// idempotencyKey는 앞뒤 공백을 지운 값이다
public record PayCommand(long userId, long holdId, String idempotencyKey, Optional<PgScenario> pgScenario) {
}
