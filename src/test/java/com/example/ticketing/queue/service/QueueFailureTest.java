package com.example.ticketing.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.redis.TestTicketingRedis;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.queue.repository.QueueRedisStore;
import com.example.ticketing.seat.service.SeatCatalog;
import com.example.ticketing.support.CapturedLogs;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

// 닫힌 포트로 만든 예매 연결로 Redis 장애를 재현한다
@IntegrationTest
class QueueFailureTest {

    private static final long USER = 7_200_000_001L;

    @Autowired
    private TicketingProperties props;
    @Autowired
    private SeatCatalog seatCatalog;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DbClock dbClock;

    private TicketingRedis deadRedis;
    private QueueService deadService;

    @BeforeEach
    void setUp() throws IOException {
        deadRedis = TestTicketingRedis.connectTo("localhost", closedPort(), props);
        deadService = new QueueService(new QueueRedisStore(deadRedis), seatCatalog, dbClock, props);
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
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.QUEUE_UNAVAILABLE);
        assertThatThrownBy(() -> deadService.status(pid, USER, USER + ".token"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.QUEUE_UNAVAILABLE);
    }

    @Test
    @DisplayName("장애 중 503이 여러 번 나도 10초 안에는 warn이 한 줄이다")
    void unavailableWarnThrottled() {
        long pid = fixtures.newPerformance(1, 1, 1);

        try (CapturedLogs logs = CapturedLogs.of(QueueService.class)) {
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> deadService.enter(pid, USER))
                        .isInstanceOf(ApiException.class)
                        .extracting(e -> ((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.QUEUE_UNAVAILABLE);
            }

            assertThat(logs.warnings()).hasSize(1);
            assertThat(logs.warnings().get(0)).contains("op=enter").contains("앞서 생략 0건");
        }
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
