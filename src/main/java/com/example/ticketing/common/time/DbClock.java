package com.example.ticketing.common.time;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 서버마다 시계가 달라도 만료 판정이 갈리지 않게 DB 시각을 기준으로 쓴다
@Component
public class DbClock {

    private static final long OFFSET_TTL_NANOS = Duration.ofSeconds(10).toNanos();

    private final JdbcTemplate jdbcTemplate;
    private final Clock jvmClock;
    private final LongSupplier nanoTime;
    private final Object offsetLock = new Object();
    private volatile Offset offset;

    @Autowired
    public DbClock(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemDefaultZone(), System::nanoTime);
    }

    DbClock(JdbcTemplate jdbcTemplate, Clock jvmClock, LongSupplier nanoTime) {
        this.jdbcTemplate = jdbcTemplate;
        this.jvmClock = jvmClock;
        this.nanoTime = nanoTime;
    }

    public LocalDateTime now() {
        return jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);
    }

    // 요청마다 DB를 치지 않는 사전 판정용. 서버 시계에 10초마다 잰 DB 시각과의 차이를 더한다
    public LocalDateTime estimatedNow() {
        return LocalDateTime.now(jvmClock).plus(currentOffset());
    }

    private Duration currentOffset() {
        Offset current = offset;
        if (current != null && current.isFresh(nanoTime.getAsLong())) {
            return current.value();
        }
        synchronized (offsetLock) {
            current = offset;
            if (current != null && current.isFresh(nanoTime.getAsLong())) {
                return current.value();
            }
            offset = measure();
            return offset.value();
        }
    }

    // 왕복 시간의 가운데를 DB가 시각을 읽은 순간으로 본다
    private Offset measure() {
        LocalDateTime before = LocalDateTime.now(jvmClock);
        LocalDateTime db = now();
        LocalDateTime after = LocalDateTime.now(jvmClock);
        LocalDateTime middle = before.plus(Duration.between(before, after).dividedBy(2));
        return new Offset(Duration.between(middle, db), nanoTime.getAsLong());
    }

    private record Offset(Duration value, long measuredAtNanos) {

        boolean isFresh(long nowNanos) {
            return nowNanos - measuredAtNanos < OFFSET_TTL_NANOS;
        }
    }
}
