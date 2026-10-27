package com.example.ticketing.booking.payment.api;

// method는 화면이 고른 결제 수단이다. Fake PG는 수단을 구분하지 않아 지금은 받기만 한다
public record PayRequest(String method) {
}
