package com.example.ticketing.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisCommandTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class RedisFailuresTest {

    @Test
    @DisplayName("템플릿 예외와 Lettuce 예외는 원인을 담은 RedisUnavailableException으로 바뀐다")
    void translatesRedisFailures() {
        QueryTimeoutException template = new QueryTimeoutException("redis timeout");
        RedisCommandTimeoutException lettuce = new RedisCommandTimeoutException("command timed out");

        assertThatThrownBy(() -> RedisFailures.translate(() -> {
            throw template;
        })).isInstanceOf(RedisUnavailableException.class).hasCause(template);
        assertThatThrownBy(() -> RedisFailures.translate((Runnable) () -> {
            throw lettuce;
        })).isInstanceOf(RedisUnavailableException.class).hasCause(lettuce);
    }

    @Test
    @DisplayName("Redis 장애가 아닌 예외는 그대로 지나가고 정상 값은 그대로 돌려준다")
    void passesOtherExceptionsAndValues() {
        IllegalStateException bug = new IllegalStateException("boom");

        assertThatThrownBy(() -> RedisFailures.translate(() -> {
            throw bug;
        })).isSameAs(bug);
        assertThat(RedisFailures.translate(() -> "ok")).isEqualTo("ok");
    }

    @Test
    @DisplayName("describe는 번역 예외면 원인 문자열을, 아니면 예외 문자열을 돌려준다")
    void describeShowsOriginalCause() {
        QueryTimeoutException cause = new QueryTimeoutException("redis timeout");

        assertThat(RedisFailures.describe(new RedisUnavailableException(cause))).isEqualTo(cause.toString());
        assertThat(RedisFailures.describe(cause)).isEqualTo(cause.toString());
    }
}
