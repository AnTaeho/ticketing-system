package com.example.ticketing.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.support.CapturedLogs;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
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
    private Fixtures fixtures;
    @Autowired
    private TestRedis testRedis;
    @Autowired
    private TestServices services;

    private QueueService deadService;

    @BeforeEach
    void setUp() {
        deadService = services.queueService().store(TestServices.queueStore(testRedis.dead())).build();
    }

    @Test
    @DisplayName("Redis가 죽으면 진입·조회는 503 QUEUE_UNAVAILABLE로 막는다")
    void enterAndStatusFailClosed() {
        long pid = fixtures.newPerformance(1, 1, 1);

        assertThatThrownBy(() -> deadService.enter(USER, pid))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.QUEUE_UNAVAILABLE);
        assertThatThrownBy(() -> deadService.status(USER, pid, USER + ".token"))
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
                assertThatThrownBy(() -> deadService.enter(USER, pid))
                        .isInstanceOf(ApiException.class)
                        .extracting(e -> ((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.QUEUE_UNAVAILABLE);
            }

            assertThat(logs.warnings()).hasSize(1);
            assertThat(logs.warnings().get(0)).contains("op=enter").contains("앞서 생략 0건");
        }
    }

    // 선점 쪽이 이 예외를 받아 fail-open으로 처리한다
    @Test
    @DisplayName("처리열 토큰 확인(isAdmitted)은 Redis 장애를 RedisUnavailableException으로 던진다")
    void isAdmittedThrows() {
        assertThatThrownBy(() -> deadService.isAdmitted(USER, 1L, USER + ".token"))
                .isInstanceOf(RedisUnavailableException.class)
                .hasCauseInstanceOf(DataAccessException.class);
    }
}
