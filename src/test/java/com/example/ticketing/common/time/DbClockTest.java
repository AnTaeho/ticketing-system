package com.example.ticketing.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.ticketing.support.IntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class DbClockTest {

    @Autowired
    private DbClock dbClock;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("DB 시각을 LocalDateTime으로 읽고 뒤로 가지 않는다")
    void now() {
        LocalDateTime first = dbClock.now();
        LocalDateTime second = dbClock.now();

        assertThat(first).isNotNull();
        assertThat(second).isAfterOrEqualTo(first);
    }

    @Test
    @DisplayName("estimatedNow: 서버 시계가 DB보다 2시간 늦거나 빨라도 DB 시각에 가깝다")
    void estimatedNowFollowsDbEvenIfJvmClockIsSkewed() {
        for (Duration skew : new Duration[] {Duration.ofHours(-2), Duration.ofHours(2)}) {
            DbClock skewed = new DbClock(jdbcTemplate, Clock.offset(Clock.systemDefaultZone(), skew), System::nanoTime);

            LocalDateTime estimated = skewed.estimatedNow();
            LocalDateTime db = dbClock.now();

            assertThat(estimated).isCloseTo(db, within(1, ChronoUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("estimatedNow: DB 시각과의 차이는 10초 동안 다시 재지 않고, 지나면 한 번 다시 잰다")
    void offsetIsCachedForTenSeconds() {
        JdbcTemplate spied = spy(jdbcTemplate);
        AtomicLong nanos = new AtomicLong(0);
        DbClock clock = new DbClock(spied, Clock.systemDefaultZone(), nanos::get);

        for (int i = 0; i < 100; i++) {
            clock.estimatedNow();
        }
        verify(spied, times(1)).queryForObject(anyString(), eq(LocalDateTime.class));

        nanos.addAndGet(Duration.ofSeconds(9).toNanos());
        clock.estimatedNow();
        verify(spied, times(1)).queryForObject(anyString(), eq(LocalDateTime.class));

        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        clock.estimatedNow();
        clock.estimatedNow();
        verify(spied, times(2)).queryForObject(anyString(), eq(LocalDateTime.class));
    }
}
