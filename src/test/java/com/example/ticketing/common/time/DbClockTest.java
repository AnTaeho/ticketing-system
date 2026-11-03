package com.example.ticketing.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.IntegrationTest;
import java.time.LocalDateTime;
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
    @DisplayName("DB 시각을 마이크로초까지 LocalDateTime으로 읽고 뒤로 가지 않는다")
    void now() {
        LocalDateTime before = nowMicros();
        LocalDateTime first = dbClock.now();
        LocalDateTime second = dbClock.now();
        LocalDateTime after = nowMicros();

        assertThat(first).isAfterOrEqualTo(before);
        assertThat(second).isAfterOrEqualTo(first).isBeforeOrEqualTo(after);
    }

    private LocalDateTime nowMicros() {
        return jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);
    }
}
