package com.example.ticketing.outbox.domain;

// 실패 한 번을 기록할 값. attempt는 이번 실패까지 센 횟수다
public record FailedAttempt(OutboxTaskStatus status, int attempt, long backoffSeconds, String lastError) {
}
