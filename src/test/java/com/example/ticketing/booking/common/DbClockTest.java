package com.example.ticketing.booking.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.support.BookingIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@BookingIntegrationTest
class DbClockTest {

    @Autowired
    private DbClock dbClock;

    @Test
    @DisplayName("DB 시각을 LocalDateTime으로 읽고 뒤로 가지 않는다")
    void now() {
        LocalDateTime first = dbClock.now();
        LocalDateTime second = dbClock.now();

        assertThat(first).isNotNull();
        assertThat(second).isAfterOrEqualTo(first);
    }
}
