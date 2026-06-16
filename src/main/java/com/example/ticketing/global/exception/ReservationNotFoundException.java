package com.example.ticketing.global.exception;

public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(String ticketToken) {
        super("예약 정보를 찾을 수 없습니다. ticketToken=%s".formatted(ticketToken));
    }
}
