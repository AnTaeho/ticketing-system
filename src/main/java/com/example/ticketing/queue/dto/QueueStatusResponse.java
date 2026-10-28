package com.example.ticketing.queue.dto;

import com.example.ticketing.queue.domain.QueueTokenStatus;

public record QueueStatusResponse(QueueTokenStatus status, long position, long estimatedWaitSeconds) {

    public static QueueStatusResponse from(QueueStatusResult result) {
        return new QueueStatusResponse(result.status(), result.position(), result.estimatedWaitSeconds());
    }
}
