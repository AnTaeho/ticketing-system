package com.example.ticketing.dashboard.domain;

public enum LockType {
    NO_LOCK,
    PESSIMISTIC,
    OPTIMISTIC,
    LETTUCE_SPIN,
    REDISSON_PUBSUB,
    KAFKA_QUEUE,
    WAITING_QUEUE
}
