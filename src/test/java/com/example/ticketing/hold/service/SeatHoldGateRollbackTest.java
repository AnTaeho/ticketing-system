package com.example.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.RedisKeyCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

@IntegrationTest
class SeatHoldGateRollbackTest {

    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;
    @Autowired
    private TicketingRedis bookingRedis;
    @MockitoSpyBean
    private SeatHoldTransaction seatHoldTransaction;

    @Test
    @DisplayName("게이트 통과 뒤 persistHold가 DB 예외로 실패하면 롤백되고 게이트 키도 남지 않는다")
    void dbExceptionReleasesGateKeys() {
        long pid = fixtures.newPerformance(1, 1, 4);
        try {
            List<Long> target = jdbcTemplate.queryForList(
                    "SELECT id FROM seat WHERE performance_id = ? ORDER BY id LIMIT 2", Long.class, pid);
            long userId = fixtures.newUser();
            // 트랜잭션 프록시가 감싼 스파이에 건다. 실제로 쓰고 나서 실패해 롤백까지 거치게 한다
            SeatHoldTransaction spy = AopTestUtils.getUltimateTargetObject(seatHoldTransaction);
            doAnswer(invocation -> {
                invocation.callRealMethod();
                throw new DataAccessResourceFailureException("db connection lost");
            }).when(spy).persistHold(anyLong(), any(), anyString());
            double gateOkBefore = meterRegistry.counter(Metrics.HOLD_GATE, Tag.RESULT, "ok").count();
            double releaseFailureBefore = meterRegistry.counter(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "release").count();

            String queueToken = fixtures.admit(pid, userId);
            assertThatThrownBy(() -> seatHoldService.hold(new CreateHoldCommand(userId, pid, target, queueToken)))
                    .isInstanceOf(DataAccessResourceFailureException.class);

            assertThat(meterRegistry.counter(Metrics.HOLD_GATE, Tag.RESULT, "ok").count() - gateOkBefore).isEqualTo(1);
            assertThat(meterRegistry.counter(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "release").count())
                    .isEqualTo(releaseFailureBefore);
            assertThat(redisKeyCleaner.gateKeys(pid)).isEmpty();
            // 서버 오류로 끝난 선점은 토큰을 소모하지 않는다
            assertThat(bookingRedis.template().opsForZSet().score(RedisKeys.queueProcessing(pid), queueToken)).isNotNull();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE performance_id = ?",
                    Long.class, pid)).isZero();
            assertThat(jdbcTemplate.queryForList("SELECT DISTINCT status FROM seat WHERE performance_id = ?",
                    String.class, pid)).containsExactly("AVAILABLE");
        } finally {
            redisKeyCleaner.clean(pid);
        }
    }
}
