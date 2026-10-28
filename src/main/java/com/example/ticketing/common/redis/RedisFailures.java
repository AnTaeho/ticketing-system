package com.example.ticketing.common.redis;

import io.lettuce.core.RedisException;
import org.springframework.dao.DataAccessException;

// 템플릿이 바꿔 던진 예외와 Lettuce가 그대로 던진 예외를 모두 Redis 장애로 본다
public final class RedisFailures {

    private RedisFailures() {
    }

    public static boolean isFailure(Throwable e) {
        return e instanceof DataAccessException || e instanceof RedisException;
    }
}
