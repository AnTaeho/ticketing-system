package com.example.ticketing.global.chaos;

public enum ChaosType {
    NONE,
    HIKARI_CONSTRAINED,
    REDIS_DELAY,
    REDIS_BLOCK,
    KAFKA_PAUSED
}
