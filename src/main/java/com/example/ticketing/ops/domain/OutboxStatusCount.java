package com.example.ticketing.ops.domain;

public record OutboxStatusCount(String taskType, String status, long count) {
}
