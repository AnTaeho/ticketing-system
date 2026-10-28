package com.example.ticketing.fakepg.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class FakePgSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("fake_pg_transaction: order_id varchar(40) PK, varchar 상태, datetime(6) 시각")
    void fakePgTransaction() {
        String ddl = jdbcTemplate.queryForObject("SHOW CREATE TABLE fake_pg_transaction", (rs, i) -> rs.getString(2));
        System.out.println("=== SHOW CREATE TABLE fake_pg_transaction ===\n" + ddl);

        assertThat(ddl)
                .contains("`order_id` varchar(40) NOT NULL")
                .contains("PRIMARY KEY (`order_id`)")
                .contains("`amount` int NOT NULL")
                .contains("`status` varchar(12) NOT NULL")
                .contains("`pg_tx_id` varchar(64) DEFAULT NULL")
                .contains("`created_at` datetime(6) NOT NULL")
                .contains("`updated_at` datetime(6) NOT NULL");
    }
}
