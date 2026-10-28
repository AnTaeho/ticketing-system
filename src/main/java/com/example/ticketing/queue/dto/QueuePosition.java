package com.example.ticketing.queue.dto;

import com.example.ticketing.queue.domain.QueueTokenStatus;

// position은 대기 중일 때만 1부터 센다. 그 밖에는 0이다
public record QueuePosition(QueueTokenStatus status, long position, long estimatedWaitSeconds) {

    public static QueuePosition processing() {
        return new QueuePosition(QueueTokenStatus.PROCESSING, 0, 0);
    }

    public static QueuePosition waiting(long position, long estimatedWaitSeconds) {
        return new QueuePosition(QueueTokenStatus.WAITING, position, estimatedWaitSeconds);
    }

    public static QueuePosition expired() {
        return new QueuePosition(QueueTokenStatus.EXPIRED, 0, 0);
    }
}
