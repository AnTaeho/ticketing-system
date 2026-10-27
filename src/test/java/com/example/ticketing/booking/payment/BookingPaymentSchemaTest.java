package com.example.ticketing.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.support.BookingIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@BookingIntegrationTest
class BookingPaymentSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("booking_payment: hold·멱등키·주문 유니크, 대사 인덱스, varchar 상태, datetime(6) 시각")
    void bookingPayment() {
        assertThat(ddl("booking_payment"))
                .contains("UNIQUE KEY `uk_payment_hold` (`hold_id`)")
                .contains("UNIQUE KEY `uk_payment_idem` (`user_id`,`idempotency_key`)")
                .contains("UNIQUE KEY `uk_payment_order` (`order_id`)")
                .contains("KEY `idx_payment_check` (`status`,`next_check_at`)")
                .contains("`idempotency_key` varchar(64) NOT NULL")
                .contains("`order_id` varchar(40) NOT NULL")
                .contains("`amount` int NOT NULL")
                .contains("`status` varchar(12) NOT NULL")
                .contains("`pg_tx_id` varchar(64) DEFAULT NULL")
                .contains("`last_error` varchar(200) DEFAULT NULL")
                .contains("`next_check_at` datetime(6) DEFAULT NULL")
                .contains("`created_at` datetime(6) NOT NULL")
                .contains("`updated_at` datetime(6) NOT NULL");
    }

    @Test
    @DisplayName("booking: hold·예매번호 유니크, 사용자 인덱스, payment_id, datetime(6) 시각")
    void booking() {
        assertThat(ddl("booking"))
                .contains("UNIQUE KEY `uk_booking_hold` (`hold_id`)")
                .contains("UNIQUE KEY `uk_booking_number` (`booking_number`)")
                .contains("KEY `idx_booking_user` (`user_id`,`created_at`)")
                .contains("`booking_number` varchar(20) NOT NULL")
                .contains("`payment_id` bigint NOT NULL")
                .contains("`total_amount` int NOT NULL")
                .contains("`status` varchar(12) NOT NULL")
                .contains("`created_at` datetime(6) NOT NULL")
                .contains("`cancelled_at` datetime(6) DEFAULT NULL");
    }

    @Test
    @DisplayName("booking_seat: PK (booking_id, seat_id), 좌석 사본 컬럼")
    void bookingSeat() {
        assertThat(ddl("booking_seat"))
                .contains("PRIMARY KEY (`booking_id`,`seat_id`)")
                .contains("`section` varchar(10) NOT NULL")
                .contains("`row_no` int NOT NULL")
                .contains("`seat_no` int NOT NULL")
                .contains("`grade` varchar(10) NOT NULL")
                .contains("`price` int NOT NULL");
    }

    @Test
    @DisplayName("outbox_task: (task_type, aggregate_id) 유니크, 폴링 인덱스, payload TEXT")
    void outboxTask() {
        assertThat(ddl("outbox_task"))
                .contains("UNIQUE KEY `uk_outbox_dedup` (`task_type`,`aggregate_id`)")
                .contains("KEY `idx_outbox_poll` (`status`,`next_attempt_at`)")
                .contains("`task_type` varchar(30) NOT NULL")
                .contains("`aggregate_id` bigint NOT NULL")
                .contains("`payload` text NOT NULL")
                .contains("`status` varchar(12) NOT NULL")
                .contains("`attempt` int NOT NULL")
                .contains("`next_attempt_at` datetime(6) NOT NULL")
                .contains("`locked_until` datetime(6) DEFAULT NULL")
                .contains("`last_error` varchar(500) DEFAULT NULL");
    }

    private String ddl(String table) {
        String ddl = jdbcTemplate.queryForObject("SHOW CREATE TABLE " + table, (rs, i) -> rs.getString(2));
        System.out.println("=== SHOW CREATE TABLE " + table + " ===\n" + ddl);
        return ddl;
    }
}
