package com.example.ticketing.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

// 예매 테스트 메타 애너테이션들이 같은 속성을 쓰게 한 곳에 둔다. 예매 테스트 데이터는 전용 DB·Redis DB 1에 쌓는다
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
@TestPropertySource(properties = {
        "booking.seed.enabled=false",
        "booking.hold.expiry-enabled=false",
        "booking.payment.reconcile-enabled=false",
        "booking.outbox.worker-enabled=false",
        "booking.queue.promote-enabled=false",
        "booking.seatmap.snapshot-enabled=false",
        "booking.ops.orphan-enabled=false",
        "booking.redis.database=1",
        "spring.datasource.url=jdbc:mysql://localhost:3306/ticketing_booking_test"
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
})
@Import({Fixtures.class, RedisKeyCleaner.class})
@interface TestSetup {
}
