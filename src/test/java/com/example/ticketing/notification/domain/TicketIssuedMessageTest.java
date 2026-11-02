package com.example.ticketing.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TicketIssuedMessageTest {

    @Test
    @DisplayName("좌석이 1석이면 알림에 「외 N석」 없이 그 좌석만 적는다")
    void singleSeatHasNoOthers() {
        IssuedBookingRow booking = new IssuedBookingRow(1L, "B20261101-000042", "테스트 공연",
                LocalDateTime.of(2026, 11, 2, 19, 30));

        String message = TicketIssuedMessage.of(booking, List.of(new IssuedSeatRow("A", 1, 1)));

        assertThat(message)
                .isEqualTo("「테스트 공연」 2026.11.2(월) 19:30 A구역 1열 1번 예매가 확정됐어요. 예매번호 B20261101-000042");
    }
}
