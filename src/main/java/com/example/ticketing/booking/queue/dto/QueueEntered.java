package com.example.ticketing.booking.queue.dto;

import com.example.ticketing.booking.queue.QueueTokenStatus;

public record QueueEntered(String token, QueueTokenStatus status) {
}
