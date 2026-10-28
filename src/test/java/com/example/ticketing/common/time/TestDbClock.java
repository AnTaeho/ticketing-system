package com.example.ticketing.common.time;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

// DB 시계가 서버 시계와 어긋난 상황을 만든다. DB가 돌려주는 시각에만 skew를 더한다
public final class TestDbClock {

    private TestDbClock() {
    }

    public static DbClock withDbShift(JdbcTemplate jdbcTemplate, Duration skew) {
        JdbcTemplate shifted = new JdbcTemplate(jdbcTemplate.getDataSource()) {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType) {
                T value = super.queryForObject(sql, requiredType);
                return value instanceof LocalDateTime time ? requiredType.cast(time.plus(skew)) : value;
            }
        };
        return new DbClock(shifted, Clock.systemDefaultZone(), System::nanoTime);
    }
}
