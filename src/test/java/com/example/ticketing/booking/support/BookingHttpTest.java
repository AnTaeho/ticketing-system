package com.example.ticketing.booking.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;

// Fake PG를 실제 HTTP로 부르는 테스트만 쓴다. 나머지 결제 테스트는 StubPgClient로
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@BookingTestSetup
public @interface BookingHttpTest {
}
