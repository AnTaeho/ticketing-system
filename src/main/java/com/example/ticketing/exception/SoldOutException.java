package com.example.ticketing.exception;

public class SoldOutException extends RuntimeException {

    public SoldOutException(Long concertId) {
        super("매진되었습니다. concertId=" + concertId);
    }
}
