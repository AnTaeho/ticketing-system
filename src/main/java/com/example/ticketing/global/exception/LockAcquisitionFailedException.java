package com.example.ticketing.global.exception;

public class LockAcquisitionFailedException extends RuntimeException {

    public LockAcquisitionFailedException(Long concertId) {
        super("락 획득에 실패했습니다. concertId=" + concertId);
    }
}
