package com.example.ticketing.common.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.CapturedLogs;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ThrottledWarnTest {

    @Test
    @DisplayName("10초 안의 실패는 warn 한 줄로 묶고 지나면 생략 건수를 붙여 다시 남긴다")
    void oneWarnPerInterval() {
        AtomicLong clock = new AtomicLong(0);
        ThrottledWarn warn = new ThrottledWarn(LoggerFactory.getLogger(ThrottledWarnTest.class), clock::get);

        try (CapturedLogs logs = CapturedLogs.of(ThrottledWarnTest.class)) {
            for (int i = 0; i < 5; i++) {
                warn.warn("실패 {}", i);
                clock.addAndGet(Duration.ofSeconds(1).toNanos());
            }

            assertThat(logs.warnings()).containsExactly("실패 0 (앞서 생략 0건)");

            clock.set(Duration.ofSeconds(10).toNanos());
            warn.warn("실패 {}", 5);

            assertThat(logs.warnings()).containsExactly("실패 0 (앞서 생략 0건)", "실패 5 (앞서 생략 4건)");
        }
    }
}
