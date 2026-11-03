package com.example.ticketing.common.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.Delay;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Boot 연결에 Redis 장애를 빨리 알아채는 설정만 더한다. 타임아웃·DB 번호는 spring.data.redis.*에 있다
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    // 기본 상한(30초)이면 Redis가 돌아와도 재연결까지 수십 초가 걸린다
    static final Duration RECONNECT_DELAY_MAX = Duration.ofSeconds(2);

    @Bean
    ClientResourcesBuilderCustomizer reconnectDelayCap() {
        return resources -> resources.reconnectDelay(
                Delay.exponential(Duration.ZERO, RECONNECT_DELAY_MAX, 2, TimeUnit.MILLISECONDS));
    }

    // 끊긴 동안 명령을 쌓아 두지 않고 바로 실패시켜 DB 판정으로 넘어가게 한다
    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhenDisconnected() {
        return options -> options.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
