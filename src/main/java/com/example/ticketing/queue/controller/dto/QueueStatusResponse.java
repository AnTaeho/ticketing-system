package com.example.ticketing.queue.controller.dto;

import static com.example.ticketing.queue.QueueConst.*;

public record QueueStatusResponse(String status, Integer position, Integer estimatedWaitSeconds) {

    public static QueueStatusResponse processing() {
        return new QueueStatusResponse(STATUS_PROCESSING, 0, 0);
    }

    public static QueueStatusResponse waiting(int position, int estimatedWaitSeconds) {
        return new QueueStatusResponse(STATUS_WAITING, position, estimatedWaitSeconds);
    }

    public static QueueStatusResponse notInQueue() {
        return new QueueStatusResponse(STATUS_NOT_IN_QUEUE, 0, 0);
    }
}
