package com.example.ticketing.booking.queue;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.common.BookingRedis;
import com.example.ticketing.booking.common.TestBookingRedis;
import com.example.ticketing.booking.seat.SeatCatalog;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

// 닫힌 포트로 만든 예매 연결로 Redis 장애를 재현한다
@BookingIntegrationTest
class BookingQueueFailureTest {

    private static final long USER = 7_200_000_001L;

    @Autowired
    private BookingProperties props;
    @Autowired
    private SeatCatalog seatCatalog;
    @Autowired
    private BookingFixtures fixtures;

    private BookingRedis deadRedis;
    private BookingQueueService deadService;

    @BeforeEach
    void setUp() throws IOException {
        deadRedis = TestBookingRedis.connectTo("localhost", closedPort(), props);
        deadService = new BookingQueueService(new BookingQueueRepository(deadRedis), seatCatalog, props);
    }

    @AfterEach
    void tearDown() {
        deadRedis.destroy();
    }

    @Test
    @DisplayName("진입·조회는 Redis 장애 때 503 QUEUE_UNAVAILABLE(fail-closed)")
    void enterAndStatusFailClosed() {
        long pid = fixtures.newPerformance(1, 1, 1);

        assertThatThrownBy(() -> deadService.enter(pid, USER))
                .isInstanceOf(BookingException.class)
                .extracting(e -> ((BookingException) e).getCode())
                .isEqualTo(BookingErrorCode.QUEUE_UNAVAILABLE);
        assertThatThrownBy(() -> deadService.status(pid, USER, USER + ".token"))
                .isInstanceOf(BookingException.class)
                .extracting(e -> ((BookingException) e).getCode())
                .isEqualTo(BookingErrorCode.QUEUE_UNAVAILABLE);
    }

    @Test
    @DisplayName("isAdmitted는 Redis 예외를 그대로 던진다(선점 쪽에서 fail-open 처리)")
    void isAdmittedThrows() {
        assertThatThrownBy(() -> deadService.isAdmitted(1L, USER + ".token", USER))
                .isInstanceOf(DataAccessException.class);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
