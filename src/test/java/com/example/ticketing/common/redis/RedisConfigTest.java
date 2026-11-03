package com.example.ticketing.common.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.IntegrationTest;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.Delay;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

@IntegrationTest
class RedisConfigTest {

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ClientResources clientResources;

    @Test
    @DisplayName("Redis 연결로 PING을 보내면 PONG이 온다")
    void ping() {
        String pong = redis.execute((RedisCallback<String>) RedisConnection::ping);

        assertThat(pong).isEqualTo("PONG");
    }

    @Test
    @DisplayName("Redis 연결은 명령 300ms·연결 500ms 타임아웃이고 끊긴 동안의 명령은 바로 거절한다")
    void failFastOptionsApplied() {
        LettuceClientConfiguration client = factory().getClientConfiguration();
        ClientOptions options = client.getClientOptions().orElseThrow();

        assertThat(client.getCommandTimeout()).isEqualTo(Duration.ofMillis(300));
        assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    @Test
    @DisplayName("Redis 연결의 재연결 간격은 지수로 늘다가 2초에서 멈춘다")
    void reconnectDelayCappedAtTwoSeconds() {
        Delay delay = clientResources.reconnectDelay();

        assertThat(factory().getClientConfiguration().getClientResources()).containsSame(clientResources);
        assertThat(delay.createDelay(1)).isLessThan(Duration.ofMillis(10));
        for (long attempt = 1; attempt <= 64; attempt++) {
            assertThat(delay.createDelay(attempt)).isLessThanOrEqualTo(Duration.ofSeconds(2));
        }
        assertThat(delay.createDelay(30)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("테스트의 Redis 연결은 DB 1을 써서 DB 0에서는 그 키가 안 보인다")
    void testsUseDatabaseOne() {
        LettuceConnectionFactory factory = factory();
        RedisStandaloneConfiguration dbZero = new RedisStandaloneConfiguration(factory.getHostName(), factory.getPort());
        LettuceConnectionFactory dbZeroFactory = new LettuceConnectionFactory(dbZero, factory.getClientConfiguration());
        dbZeroFactory.afterPropertiesSet();
        dbZeroFactory.start();
        String key = "booking-redis-test:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "1", Duration.ofMinutes(1));

            assertThat(factory.getDatabase()).isEqualTo(1);
            assertThat(redis.hasKey(key)).isTrue();
            assertThat(new StringRedisTemplate(dbZeroFactory).hasKey(key)).isFalse();
        } finally {
            redis.delete(key);
            dbZeroFactory.destroy();
        }
    }

    private LettuceConnectionFactory factory() {
        return (LettuceConnectionFactory) redis.getConnectionFactory();
    }
}
