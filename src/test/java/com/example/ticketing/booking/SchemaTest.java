package com.example.ticketing.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.support.BookingIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@BookingIntegrationTest
class SchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("app_user: 닉네임 유니크와 datetime(6)")
    void appUser() {
        String ddl = showCreateTable("app_user");

        assertThat(ddl)
                .contains("UNIQUE KEY `uk_app_user_nickname` (`nickname`)")
                .contains("`nickname` varchar(30) NOT NULL")
                .contains("`created_at` datetime(6) NOT NULL");
    }

    @Test
    @DisplayName("show_info: 공연 테이블 이름과 컬럼")
    void showInfo() {
        String ddl = showCreateTable("show_info");

        assertThat(ddl)
                .contains("`title` varchar(200) NOT NULL")
                .contains("`venue_name` varchar(100) NOT NULL")
                .contains("`description` varchar(1000)");
    }

    @Test
    @DisplayName("performance: 인덱스 두 개와 datetime(6) 시각 컬럼")
    void performance() {
        String ddl = showCreateTable("performance");

        assertThat(ddl)
                .contains("KEY `idx_perf_show` (`show_id`,`starts_at`)")
                .contains("KEY `idx_perf_open` (`open_at`,`starts_at`)")
                .contains("`starts_at` datetime(6) NOT NULL")
                .contains("`open_at` datetime(6) NOT NULL")
                .contains("`gate_close_at` datetime(6) NOT NULL");
    }

    @Test
    @DisplayName("seat: 위치 유니크, 상태·선점 인덱스, varchar 등급·상태, datetime(6) 선점 기한")
    void seat() {
        String ddl = showCreateTable("seat");

        assertThat(ddl)
                .contains("UNIQUE KEY `uk_seat_position` (`performance_id`,`section`,`row_no`,`seat_no`)")
                .contains("KEY `idx_seat_perf_status` (`performance_id`,`status`)")
                .contains("KEY `idx_seat_hold` (`hold_id`)")
                .contains("`grade` varchar(10) NOT NULL")
                .contains("`status` varchar(10) NOT NULL")
                .contains("`held_until` datetime(6) DEFAULT NULL");
    }

    private String showCreateTable(String table) {
        String ddl = jdbcTemplate.queryForObject("SHOW CREATE TABLE " + table, (rs, i) -> rs.getString(2));
        System.out.println("=== SHOW CREATE TABLE " + table + " ===\n" + ddl);
        return ddl;
    }
}
