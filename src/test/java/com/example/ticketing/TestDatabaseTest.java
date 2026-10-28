package com.example.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class TestDatabaseTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("예매 테스트는 전용 DB ticketing_booking_test에 붙는다")
    void usesDedicatedDatabase() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("ticketing_booking_test");
    }
}
