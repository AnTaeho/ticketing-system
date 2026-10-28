package com.example.ticketing.queue.dto;

import com.example.ticketing.queue.domain.QueueTokenStatus;

public record QueueEnterResult(String queueToken, QueueTokenStatus status) {
}
