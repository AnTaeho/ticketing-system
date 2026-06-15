package com.example.ticketing.waitingroom.dto;

import static com.example.ticketing.waitingroom.WaitingRoomConst.*;

public record QueueTokenResponse(String token, String status) {

    public static QueueTokenResponse processing(String token) {
        return new QueueTokenResponse(token, STATUS_PROCESSING);
    }

    public static QueueTokenResponse waiting(String token) {
        return new QueueTokenResponse(token, STATUS_WAITING);
    }
}
