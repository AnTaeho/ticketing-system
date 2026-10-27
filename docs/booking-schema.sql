-- V7 지정석 예매 스키마 (MySQL 9.3, Hibernate 7 ddl-auto로 생성된 테이블을 SHOW CREATE TABLE로 뽑음)
-- prod 프로필은 ddl-auto: validate라 이 DDL로 테이블을 먼저 만든다.

CREATE TABLE `app_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `nickname` varchar(30) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_user_nickname` (`nickname`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `show_info` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `description` varchar(1000) DEFAULT NULL,
  `title` varchar(200) NOT NULL,
  `venue_name` varchar(100) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `performance` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `gate_close_at` datetime(6) NOT NULL,
  `open_at` datetime(6) NOT NULL,
  `show_id` bigint NOT NULL,
  `starts_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_perf_show` (`show_id`,`starts_at`),
  KEY `idx_perf_open` (`open_at`,`starts_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `seat` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `grade` varchar(10) NOT NULL,
  `held_until` datetime(6) DEFAULT NULL,
  `hold_id` bigint DEFAULT NULL,
  `performance_id` bigint NOT NULL,
  `price` int NOT NULL,
  `row_no` int NOT NULL,
  `seat_no` int NOT NULL,
  `section` varchar(10) NOT NULL,
  `status` varchar(10) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seat_position` (`performance_id`,`section`,`row_no`,`seat_no`),
  KEY `idx_seat_perf_status` (`performance_id`,`status`),
  KEY `idx_seat_hold` (`hold_id`),
  CONSTRAINT `seat_chk_1` CHECK ((`grade` in (_utf8mb4'VIP',_utf8mb4'R',_utf8mb4'S',_utf8mb4'A'))),
  CONSTRAINT `seat_chk_2` CHECK ((`status` in (_utf8mb4'AVAILABLE',_utf8mb4'HELD',_utf8mb4'SOLD')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `seat_hold` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `hold_token` char(36) NOT NULL,
  `pay_deadline` datetime(6) DEFAULT NULL,
  `performance_id` bigint NOT NULL,
  `seat_count` int NOT NULL,
  `status` varchar(12) NOT NULL,
  `total_amount` int NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hold_token` (`hold_token`),
  KEY `idx_hold_expiry` (`status`,`expires_at`),
  KEY `idx_hold_user` (`user_id`,`performance_id`,`status`),
  CONSTRAINT `seat_hold_chk_1` CHECK ((`status` in (_utf8mb4'HELD',_utf8mb4'PAYING',_utf8mb4'CONFIRMED',_utf8mb4'EXPIRED',_utf8mb4'RELEASED',_utf8mb4'REFUNDING')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `booking_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `hold_id` bigint NOT NULL,
  `idempotency_key` varchar(64) NOT NULL,
  `last_error` varchar(200) DEFAULT NULL,
  `next_check_at` datetime(6) DEFAULT NULL,
  `order_id` varchar(40) NOT NULL,
  `pg_tx_id` varchar(64) DEFAULT NULL,
  `status` varchar(12) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_hold` (`hold_id`),
  UNIQUE KEY `uk_payment_idem` (`user_id`,`idempotency_key`),
  UNIQUE KEY `uk_payment_order` (`order_id`),
  KEY `idx_payment_check` (`status`,`next_check_at`),
  CONSTRAINT `booking_payment_chk_1` CHECK ((`status` in (_utf8mb4'REQUESTED',_utf8mb4'UNKNOWN',_utf8mb4'APPROVED',_utf8mb4'DECLINED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `booking` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `booking_number` varchar(20) NOT NULL,
  `cancelled_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `hold_id` bigint NOT NULL,
  `payment_id` bigint NOT NULL,
  `performance_id` bigint NOT NULL,
  `status` varchar(12) NOT NULL,
  `total_amount` int NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_booking_hold` (`hold_id`),
  UNIQUE KEY `uk_booking_number` (`booking_number`),
  KEY `idx_booking_user` (`user_id`,`created_at`),
  CONSTRAINT `booking_chk_1` CHECK ((`status` in (_utf8mb4'CONFIRMED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `booking_seat` (
  `booking_id` bigint NOT NULL,
  `seat_id` bigint NOT NULL,
  `grade` varchar(10) NOT NULL,
  `price` int NOT NULL,
  `row_no` int NOT NULL,
  `seat_no` int NOT NULL,
  `section` varchar(10) NOT NULL,
  PRIMARY KEY (`booking_id`,`seat_id`),
  CONSTRAINT `booking_seat_chk_1` CHECK ((`grade` in (_utf8mb4'VIP',_utf8mb4'R',_utf8mb4'S',_utf8mb4'A')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `outbox_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `aggregate_id` bigint NOT NULL,
  `attempt` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `last_error` varchar(500) DEFAULT NULL,
  `locked_until` datetime(6) DEFAULT NULL,
  `next_attempt_at` datetime(6) NOT NULL,
  `payload` text NOT NULL,
  `status` varchar(12) NOT NULL,
  `task_type` varchar(30) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outbox_dedup` (`task_type`,`aggregate_id`),
  KEY `idx_outbox_poll` (`status`,`next_attempt_at`),
  CONSTRAINT `outbox_task_chk_1` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'DONE',_utf8mb4'FAILED'))),
  CONSTRAINT `outbox_task_chk_2` CHECK ((`task_type` in (_utf8mb4'TICKET_ISSUED',_utf8mb4'REFUND_REQUESTED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `booking_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `message` varchar(300) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_booking` (`booking_id`),
  KEY `idx_notification_user` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `fake_pg_transaction` (
  `order_id` varchar(40) NOT NULL,
  `amount` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `pg_tx_id` varchar(64) DEFAULT NULL,
  `status` varchar(12) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`order_id`),
  CONSTRAINT `fake_pg_transaction_chk_1` CHECK ((`status` in (_utf8mb4'PROCESSING',_utf8mb4'APPROVED',_utf8mb4'DECLINED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

