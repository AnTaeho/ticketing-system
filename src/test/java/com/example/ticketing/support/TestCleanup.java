package com.example.ticketing.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

// 테스트마다 픽스처가 만든 회차의 Redis 키와 장애용 연결을 치운다. 테스트 클래스가 정리를 따로 챙기지 않게 한다
public class TestCleanup implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        ApplicationContext context = SpringExtension.getApplicationContext(extensionContext);
        context.getBean(Fixtures.class).cleanUp();
        context.getBean(TestRedis.class).cleanUp();
    }
}
