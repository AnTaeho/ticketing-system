package com.example.ticketing.common.time;

import java.time.Clock;
import java.util.function.LongSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 시계를 빈으로 두어 테스트가 시각을 바꿔 끼울 수 있게 한다. 업무 시각은 DbClock을 쓴다
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    // 경과 시간 전용. 벽시계가 바뀌어도 뒤로 가지 않는다
    @Bean
    public LongSupplier nanoClock() {
        return System::nanoTime;
    }
}
