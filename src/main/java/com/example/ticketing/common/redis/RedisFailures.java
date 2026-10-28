package com.example.ticketing.common.redis;

import io.lettuce.core.RedisException;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

// Redis를 만지는 코드는 호출을 translate로 감싸 장애를 RedisUnavailableException 하나로 던진다
public final class RedisFailures {

    private RedisFailures() {
    }

    public static <T> T translate(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            if (!isFailure(e)) {
                throw e;
            }
            throw new RedisUnavailableException(e);
        }
    }

    public static void translate(Runnable call) {
        translate(() -> {
            call.run();
            return null;
        });
    }

    // 로그에는 번역 전 원인 문자열을 남긴다
    public static String describe(Throwable e) {
        if (e instanceof RedisUnavailableException && e.getCause() != null) {
            return e.getCause().toString();
        }
        return e.toString();
    }

    // 템플릿이 바꿔 던진 예외와 Lettuce가 그대로 던진 예외를 모두 Redis 장애로 본다
    private static boolean isFailure(Throwable e) {
        return e instanceof DataAccessException || e instanceof RedisException;
    }
}
