package com.example.ticketing.booking.common;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 서버마다 시계가 달라도 만료 판정이 갈리지 않게 DB 시각을 기준으로 쓴다
@Component
@RequiredArgsConstructor
public class DbClock {

    private final JdbcTemplate jdbcTemplate;

    public LocalDateTime now() {
        return jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);
    }
}
