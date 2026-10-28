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
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.support.Counters;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestRedis;
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
    private TestDb db;
    @Autowired
    private TestRedis testRedis;
    @Autowired
    private Counters counters;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean
    private SeatHoldTransaction seatHoldTransaction;

    @Test
    @DisplayName("게이트 통과 뒤 DB 저장이 실패하면 롤백되고 게이트 키도 남지 않는다")
    void dbExceptionReleasesGateKeys() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = db.seatIds(pid).subList(0, 2);
        long userId = fixtures.newUser();
        // 트랜잭션 프록시가 감싼 스파이에 건다. 실제로 쓰고 나서 실패해 롤백까지 거치게 한다
        SeatHoldTransaction spy = AopTestUtils.getUltimateTargetObject(seatHoldTransaction);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("db connection lost");
        }).when(spy).persistHold(anyLong(), any(), anyString());
        double gateOkBefore = counters.count(Metrics.HOLD_GATE, Tag.RESULT, "ok");
        double releaseFailureBefore = counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "release");
        String queueToken = fixtures.admit(pid, userId);

        assertThatThrownBy(() -> seatHoldService.hold(new CreateHoldCommand(userId, pid, target, queueToken)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(counters.count(Metrics.HOLD_GATE, Tag.RESULT, "ok") - gateOkBefore).isEqualTo(1);
        assertThat(counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "release")).isEqualTo(releaseFailureBefore);
        assertThat(testRedis.gateKeys(pid)).isEmpty();
        // 서버 오류로 끝난 선점은 토큰을 소모하지 않는다
        assertThat(testRedis.template().opsForZSet().score(RedisKeys.queueProcessing(pid), queueToken)).isNotNull();
        assertThat(db.holdCount(pid)).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT status FROM seat WHERE performance_id = ?",
                String.class, pid)).containsExactly("AVAILABLE");
    }
}
