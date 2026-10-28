package com.example.ticketing.queue.domain;

// 진입 결과. 이미 살아 있는 토큰이 있으면 그 토큰과 상태다
public record QueuePlacement(QueueTokenStatus status, String queueToken) {
}
