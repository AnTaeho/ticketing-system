package com.example.ticketing.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 화면이 보내는 결제 수단(method)은 Fake PG가 구분하지 않아 읽지 않는다. 본문은 형식 오류만 400으로 걸러 낸다
@JsonIgnoreProperties(ignoreUnknown = true)
public record PayRequest() {
}
