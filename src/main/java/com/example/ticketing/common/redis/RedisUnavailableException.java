package com.example.ticketing.common.redis;

// Redis 장애를 저장소 계층이 이 예외로 바꿔 던진다. 원인 예외는 cause로 남는다
public class RedisUnavailableException extends RuntimeException {

    public RedisUnavailableException(RuntimeException cause) {
        super(cause.getMessage(), cause);
    }
}
