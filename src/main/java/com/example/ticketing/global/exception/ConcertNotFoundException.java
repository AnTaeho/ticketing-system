package com.example.ticketing.global.exception;

public class ConcertNotFoundException extends RuntimeException {

    public ConcertNotFoundException(Long concertId) {
        super("공연을 찾을 수 없습니다. concertId=" + concertId);
    }
}
