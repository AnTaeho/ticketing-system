package com.example.ticketing.queue.controller.dto;

import static com.example.ticketing.queue.QueueConst.*;

public record QueueTokenResponse(String token, String status) {

    public static QueueTokenResponse processing(String token) {
        return new QueueTokenResponse(token, STATUS_PROCESSING);
    }

    public static QueueTokenResponse waiting(String token) {
        return new QueueTokenResponse(token, STATUS_WAITING);
    }
}
