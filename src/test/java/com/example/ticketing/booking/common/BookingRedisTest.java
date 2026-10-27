package com.example.ticketing.booking.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.support.BookingIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

@BookingIntegrationTest
class BookingRedisTest {

    @Autowired
    private BookingRedis bookingRedis;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("예매 전용 연결로 PING이 된다")
    void ping() {
        String pong = bookingRedis.template().execute((RedisCallback<String>) RedisConnection::ping);

        assertThat(pong).isEqualTo("PONG");
    }

    @Test
    @DisplayName("예매 전용 연결은 빈으로 노출되지 않아 기본 Redis 빈이 하나씩 그대로 있다")
    void defaultRedisBeansUnchanged() {
        assertThat(context.getBeansOfType(StringRedisTemplate.class)).hasSize(1);
        assertThat(context.getBeansOfType(RedisConnectionFactory.class)).hasSize(1);

        StringRedisTemplate defaultTemplate = context.getBean(StringRedisTemplate.class);
        assertThat(defaultTemplate).isNotSameAs(bookingRedis.template());
        assertThat(defaultTemplate.getConnectionFactory())
                .isNotSameAs(bookingRedis.template().getConnectionFactory());
    }

    @Test
    @DisplayName("예매 전용 연결에만 짧은 명령 타임아웃이 걸린다")
    void bookingTimeoutApplied() {
        LettuceConnectionFactory factory =
                (LettuceConnectionFactory) bookingRedis.template().getConnectionFactory();

        assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(300));
    }
}
