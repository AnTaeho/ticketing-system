package com.example.ticketing.queue.dto;

import com.example.ticketing.queue.domain.QueueTokenStatus;

// expiresInMs는 다음 신호가 없을 때 처리열 자리가 남는 시간이다
public record QueueHeartbeatResult(QueueTokenStatus status, long expiresInMs) {

    public static QueueHeartbeatResult processing(long expiresInMs) {
        return new QueueHeartbeatResult(QueueTokenStatus.PROCESSING, expiresInMs);
    }
}
