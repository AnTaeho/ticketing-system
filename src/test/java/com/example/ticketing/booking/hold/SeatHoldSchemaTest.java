package com.example.ticketing.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.support.BookingIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@BookingIntegrationTest
class SeatHoldSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("seat_hold: 토큰 유니크, 만료·사용자 인덱스, char(36) 토큰, varchar 상태, datetime(6) 시각")
    void seatHold() {
        String ddl = jdbcTemplate.queryForObject("SHOW CREATE TABLE seat_hold", (rs, i) -> rs.getString(2));
        System.out.println("=== SHOW CREATE TABLE seat_hold ===\n" + ddl);

        assertThat(ddl)
                .contains("UNIQUE KEY `uk_hold_token` (`hold_token`)")
                .contains("KEY `idx_hold_expiry` (`status`,`expires_at`)")
                .contains("KEY `idx_hold_user` (`user_id`,`performance_id`,`status`)")
                .contains("`hold_token` char(36) NOT NULL")
                .contains("`status` varchar(12) NOT NULL")
                .contains("`expires_at` datetime(6) NOT NULL")
                .contains("`pay_deadline` datetime(6) DEFAULT NULL")
                .contains("`created_at` datetime(6) NOT NULL")
                .contains("`updated_at` datetime(6) NOT NULL");
    }
}
