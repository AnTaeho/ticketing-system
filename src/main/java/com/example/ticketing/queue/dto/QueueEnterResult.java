package com.example.ticketing.queue.dto;

import com.example.ticketing.queue.domain.QueueTokenStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

// 화면이 읽는 필드 이름 token을 유지한다. 이미 살아 있는 토큰이 있으면 그 토큰과 상태다
public record QueueEnterResult(@JsonProperty("token") String queueToken, QueueTokenStatus status) {
}
