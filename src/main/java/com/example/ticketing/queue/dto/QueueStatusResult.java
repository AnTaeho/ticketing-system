package com.example.ticketing.queue.dto;

import com.example.ticketing.queue.domain.QueueTokenStatus;

// position은 대기 중일 때만 1부터 센다. 그 밖에는 0이다
public record QueueStatusResult(QueueTokenStatus status, long position, long estimatedWaitSeconds) {

    public static QueueStatusResult processing() {
        return new QueueStatusResult(QueueTokenStatus.PROCESSING, 0, 0);
    }

    public static QueueStatusResult waiting(long position, long estimatedWaitSeconds) {
        return new QueueStatusResult(QueueTokenStatus.WAITING, position, estimatedWaitSeconds);
    }

    public static QueueStatusResult expired() {
        return new QueueStatusResult(QueueTokenStatus.EXPIRED, 0, 0);
    }
}
