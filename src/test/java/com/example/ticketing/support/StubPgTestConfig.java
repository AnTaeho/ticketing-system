package com.example.ticketing.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// 결제 테스트 클래스들이 이 구성을 같이 써서 컨텍스트가 하나만 늘게 한다
@TestConfiguration(proxyBeanMethods = false)
public class StubPgTestConfig {

    @Bean
    @Primary
    StubPgClient stubPgClient() {
        return new StubPgClient();
    }
}
