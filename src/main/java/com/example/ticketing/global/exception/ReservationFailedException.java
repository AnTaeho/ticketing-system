package com.example.ticketing.global.exception;

public class ReservationFailedException extends RuntimeException {

    public ReservationFailedException(Long concertId) {
        super("예약에 실패했습니다. concertId=%d".formatted(concertId));
    }
}
