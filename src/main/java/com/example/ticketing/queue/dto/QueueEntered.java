package com.example.ticketing.queue.dto;

import com.example.ticketing.queue.domain.QueueTokenStatus;

public record QueueEntered(String token, QueueTokenStatus status) {
}
