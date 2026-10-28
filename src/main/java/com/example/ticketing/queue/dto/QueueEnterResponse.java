package com.example.ticketing.queue.dto;

import com.example.ticketing.queue.domain.QueueTokenStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

// 화면이 읽는 필드 이름 token을 유지한다
public record QueueEnterResponse(@JsonProperty("token") String queueToken, QueueTokenStatus status) {

    public static QueueEnterResponse from(QueueEnterResult result) {
        return new QueueEnterResponse(result.queueToken(), result.status());
    }
}
