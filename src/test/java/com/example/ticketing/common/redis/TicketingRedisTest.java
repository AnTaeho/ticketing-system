package com.example.ticketing.common.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestRedis;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.Delay;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

@IntegrationTest
class TicketingRedisTest {

    @Autowired
    private TicketingRedis ticketingRedis;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private TestRedis testRedis;

    @Test
    @DisplayName("예매 전용 연결로 PING을 보내면 PONG이 온다")
    void ping() {
        String pong = ticketingRedis.template().execute((RedisCallback<String>) RedisConnection::ping);

        assertThat(pong).isEqualTo("PONG");
    }

    @Test
    @DisplayName("예매 전용 연결은 빈으로 올리지 않아 Redis 빈은 Boot 기본 연결뿐이다")
    void ticketingRedisNotExposedAsBean() {
        assertThat(context.getBeansOfType(StringRedisTemplate.class)).hasSize(1);
        assertThat(context.getBeansOfType(RedisConnectionFactory.class)).hasSize(1);

        StringRedisTemplate defaultTemplate = context.getBean(StringRedisTemplate.class);
        assertThat(defaultTemplate).isNotSameAs(ticketingRedis.template());
        assertThat(defaultTemplate.getConnectionFactory())
                .isNotSameAs(ticketingRedis.template().getConnectionFactory());
    }

    @Test
    @DisplayName("예매 전용 연결에만 짧은 명령 타임아웃이 걸린다")
    void bookingTimeoutApplied() {
        LettuceConnectionFactory factory =
                (LettuceConnectionFactory) ticketingRedis.template().getConnectionFactory();

        assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    @DisplayName("예매 연결의 재연결 간격은 지수로 늘다가 2초에서 멈춘다")
    void reconnectDelayCappedAtTwoSeconds() {
        Delay delay = resources(ticketingRedis).reconnectDelay();

        assertThat(delay.createDelay(1)).isLessThan(Duration.ofMillis(10));
        for (long attempt = 1; attempt <= 64; attempt++) {
            assertThat(delay.createDelay(attempt)).isLessThanOrEqualTo(Duration.ofSeconds(2));
        }
        assertThat(delay.createDelay(30)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("연결을 닫으면(destroy) 직접 만든 ClientResources도 내린다")
    void destroyShutsDownResources() {
        TicketingRedis dead = testRedis.dead();
        ClientResources resources = resources(dead);

        dead.destroy();

        assertThat(resources.eventExecutorGroup().isShutdown()).isTrue();
    }

    @Test
    @DisplayName("테스트의 예매 연결은 DB 1을 써서 기본 연결에서는 그 키가 안 보인다")
    void testsUseDatabaseOne() {
        LettuceConnectionFactory factory =
                (LettuceConnectionFactory) ticketingRedis.template().getConnectionFactory();
        StringRedisTemplate defaultTemplate = context.getBean(StringRedisTemplate.class);
        String key = "booking-redis-test:" + UUID.randomUUID();
        try {
            ticketingRedis.template().opsForValue().set(key, "1", Duration.ofMinutes(1));

            assertThat(factory.getDatabase()).isEqualTo(1);
            assertThat(ticketingRedis.template().hasKey(key)).isTrue();
            assertThat(defaultTemplate.hasKey(key)).isFalse();
        } finally {
            ticketingRedis.template().delete(key);
        }
    }

    private static ClientResources resources(TicketingRedis redis) {
        LettuceConnectionFactory factory = (LettuceConnectionFactory) redis.template().getConnectionFactory();
        return factory.getClientConfiguration().getClientResources().orElseThrow();
    }
}
