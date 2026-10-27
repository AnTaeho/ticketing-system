package com.example.ticketing.booking.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// 예매 테스트 데이터가 시연용 ticketing DB에 섞이지 않게 전용 DB를 쓴다
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "booking.seed.enabled=false",
        "booking.hold.expiry-enabled=false",
        "spring.datasource.url=jdbc:mysql://localhost:3306/ticketing_booking_test"
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
})
@Import({BookingFixtures.class, RedisKeyCleaner.class})
public @interface BookingIntegrationTest {
}
