package com.example.ticketing.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxTaskTransactionTest {

    @Test
    @DisplayName("백오프는 2^attempt초로 늘다가 max-backoff(60초)에서 멈춘다")
    void backoffIsCapped() {
        long max = 60;

        assertThat(OutboxTaskTransaction.backoffSeconds(1, max)).isEqualTo(2);
        assertThat(OutboxTaskTransaction.backoffSeconds(5, max)).isEqualTo(32);
        assertThat(OutboxTaskTransaction.backoffSeconds(6, max)).isEqualTo(60);
        assertThat(OutboxTaskTransaction.backoffSeconds(40, max)).isEqualTo(60);
    }
}
