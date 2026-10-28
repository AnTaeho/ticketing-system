package com.example.ticketing.queue.dto;

import com.example.ticketing.queue.domain.QueueTokenStatus;

public record QueueHeartbeatResponse(QueueTokenStatus status, long expiresInMs) {

    public static QueueHeartbeatResponse from(QueueHeartbeatResult result) {
        return new QueueHeartbeatResponse(result.status(), result.expiresInMs());
    }
}
