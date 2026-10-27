package com.example.ticketing.booking.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.booking.BookingProperties;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.Delay;
import java.io.IOException;
import java.net.ServerSocket;
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

@BookingIntegrationTest
class BookingRedisTest {

    @Autowired
    private BookingRedis bookingRedis;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private BookingProperties props;

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

    @Test
    @DisplayName("예매 연결의 재연결 간격은 지수로 늘다가 2초에서 멈춘다")
    void reconnectDelayCappedAtTwoSeconds() {
        Delay delay = resources(bookingRedis).reconnectDelay();

        assertThat(delay.createDelay(1)).isLessThan(Duration.ofMillis(10));
        for (long attempt = 1; attempt <= 64; attempt++) {
            assertThat(delay.createDelay(attempt)).isLessThanOrEqualTo(Duration.ofSeconds(2));
        }
        assertThat(delay.createDelay(30)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("destroy는 연결을 닫은 뒤 직접 만든 ClientResources도 내린다")
    void destroyShutsDownResources() throws IOException {
        BookingRedis dead = TestBookingRedis.connectTo("localhost", closedPort(), props);
        ClientResources resources = resources(dead);

        dead.destroy();

        assertThat(resources.eventExecutorGroup().isShutdown()).isTrue();
    }

    @Test
    @DisplayName("테스트의 예매 연결은 Redis DB 1을 쓰고 기본 연결(DB 0)에서는 그 키가 보이지 않는다")
    void testsUseDatabaseOne() {
        LettuceConnectionFactory factory =
                (LettuceConnectionFactory) bookingRedis.template().getConnectionFactory();
        StringRedisTemplate defaultTemplate = context.getBean(StringRedisTemplate.class);
        String key = "booking-redis-test:" + UUID.randomUUID();
        try {
            bookingRedis.template().opsForValue().set(key, "1", Duration.ofMinutes(1));

            assertThat(factory.getDatabase()).isEqualTo(1);
            assertThat(bookingRedis.template().hasKey(key)).isTrue();
            assertThat(defaultTemplate.hasKey(key)).isFalse();
        } finally {
            bookingRedis.template().delete(key);
        }
    }

    private static ClientResources resources(BookingRedis redis) {
        LettuceConnectionFactory factory = (LettuceConnectionFactory) redis.template().getConnectionFactory();
        return factory.getClientConfiguration().getClientResources().orElseThrow();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
