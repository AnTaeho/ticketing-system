package com.example.ticketing.booking.common;

import com.example.ticketing.booking.BookingProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.Delay;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 기본 연결과 분리: 동결 코드(V4·V6·waitingroom)의 타임아웃을 바꾸지 않으려고.
// 팩토리·템플릿을 빈으로 올리면 Boot가 기본 Redis 연결을 만들지 않으므로 이 안에만 둔다.
@Component
public class BookingRedis implements DisposableBean {

    // 기본 상한(30초)이면 Redis가 돌아와도 재연결까지 수십 초가 걸린다
    static final Duration RECONNECT_DELAY_MAX = Duration.ofSeconds(2);

    private final ClientResources resources;
    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate template;

    BookingRedis(@Value("${spring.data.redis.host}") String host,
                 @Value("${spring.data.redis.port}") int port,
                 BookingProperties props) {
        resources = DefaultClientResources.builder()
                .reconnectDelay(Delay.exponential(Duration.ZERO, RECONNECT_DELAY_MAX, 2, TimeUnit.MILLISECONDS))
                .build();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .clientResources(resources)
                .commandTimeout(props.redis().commandTimeout())
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(props.redis().connectTimeout())
                                .build())
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build())
                .build();
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
        server.setDatabase(props.redis().database());
        factory = new LettuceConnectionFactory(server, client);
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
    }

    public StringRedisTemplate template() {
        return template;
    }

    // 직접 넘긴 리소스는 팩토리가 닫지 않으므로 연결을 닫은 뒤 따로 닫는다
    @Override
    public void destroy() {
        try {
            factory.destroy();
        } finally {
            resources.shutdown(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(3_000);
        }
    }
}
