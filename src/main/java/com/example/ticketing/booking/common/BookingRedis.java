package com.example.ticketing.booking.common;

import com.example.ticketing.booking.BookingProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
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

    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate template;

    BookingRedis(@Value("${spring.data.redis.host}") String host,
                 @Value("${spring.data.redis.port}") int port,
                 BookingProperties props) {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
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

    @Override
    public void destroy() {
        factory.destroy();
    }
}
