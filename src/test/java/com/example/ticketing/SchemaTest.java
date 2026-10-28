package com.example.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// 마이그레이션이 만든 실제 DDL을 읽어 키·컬럼 타입이 설계대로인지 본다
@IntegrationTest
class SchemaTest {

    @Autowired
    private TestDb db;

    @Test
    @DisplayName("사용자 테이블(app_user)은 닉네임이 겹치지 않게 막는다")
    void appUser() {
        String ddl = db.showCreateTable("app_user");

        assertThat(ddl)
                .contains("UNIQUE KEY `uk_app_user_nickname` (`nickname`)")
                .contains("`nickname` varchar(30) NOT NULL")
                .contains("`created_at` datetime(6) NOT NULL");
    }

    @Test
    @DisplayName("공연 테이블(show_info)은 제목·공연장·설명을 담는다")
    void showInfo() {
        String ddl = db.showCreateTable("show_info");

        assertThat(ddl)
                .contains("`title` varchar(200) NOT NULL")
                .contains("`venue_name` varchar(100) NOT NULL")
                .contains("`description` varchar(1000)");
    }

    @Test
    @DisplayName("회차 테이블(performance)은 공연별·오픈 시각별 조회 인덱스를 가진다")
    void performance() {
        String ddl = db.showCreateTable("performance");

        assertThat(ddl)
                .contains("KEY `idx_perf_show` (`show_id`,`starts_at`)")
                .contains("KEY `idx_perf_open` (`open_at`,`starts_at`)")
                .contains("`starts_at` datetime(6) NOT NULL")
                .contains("`open_at` datetime(6) NOT NULL")
                .contains("`gate_close_at` datetime(6) NOT NULL");
    }

    @Test
    @DisplayName("좌석 테이블(seat)은 같은 위치의 좌석이 둘 생기지 않게 막는다")
    void seat() {
        String ddl = db.showCreateTable("seat");

        assertThat(ddl)
                .contains("UNIQUE KEY `uk_seat_position` (`performance_id`,`section`,`row_no`,`seat_no`)")
                .contains("KEY `idx_seat_perf_status` (`performance_id`,`status`)")
                .contains("KEY `idx_seat_hold` (`hold_id`)")
                .contains("`grade` varchar(10) NOT NULL")
                .contains("`status` varchar(10) NOT NULL")
                .contains("`held_until` datetime(6) DEFAULT NULL");
    }

    @Test
    @DisplayName("선점 테이블(seat_hold)은 토큰이 겹치지 않게 막고 만료 조회 인덱스를 가진다")
    void seatHold() {
        String ddl = db.showCreateTable("seat_hold");

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

    @Test
    @DisplayName("결제 테이블(booking_payment)은 선점 하나에 결제 하나만 허용한다")
    void bookingPayment() {
        String ddl = db.showCreateTable("booking_payment");

        assertThat(ddl)
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
    @DisplayName("예매 테이블(booking)은 선점 하나에 예매 하나만 허용한다")
    void booking() {
        String ddl = db.showCreateTable("booking");

        assertThat(ddl)
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
    @DisplayName("예매 좌석 테이블(booking_seat)은 예매 당시 좌석 정보를 사본으로 담는다")
    void bookingSeat() {
        String ddl = db.showCreateTable("booking_seat");

        assertThat(ddl)
                .contains("PRIMARY KEY (`booking_id`,`seat_id`)")
                .contains("`section` varchar(10) NOT NULL")
                .contains("`row_no` int NOT NULL")
                .contains("`seat_no` int NOT NULL")
                .contains("`grade` varchar(10) NOT NULL")
                .contains("`price` int NOT NULL");
    }

    @Test
    @DisplayName("작업 테이블(outbox_task)은 같은 대상의 같은 작업을 한 번만 쌓는다")
    void outboxTask() {
        String ddl = db.showCreateTable("outbox_task");

        assertThat(ddl)
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

    @Test
    @DisplayName("알림 테이블(notification)은 예매 하나에 알림 하나만 허용한다")
    void notification() {
        String ddl = db.showCreateTable("notification");

        assertThat(ddl)
                .contains("UNIQUE KEY `uk_notification_booking` (`booking_id`)")
                .contains("KEY `idx_notification_user` (`user_id`,`created_at`)")
                .contains("`message` varchar(300) NOT NULL")
                .contains("`created_at` datetime(6) NOT NULL");
    }

    @Test
    @DisplayName("가짜 PG 테이블(fake_pg_transaction)은 주문번호를 기본 키로 쓴다")
    void fakePgTransaction() {
        String ddl = db.showCreateTable("fake_pg_transaction");

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
