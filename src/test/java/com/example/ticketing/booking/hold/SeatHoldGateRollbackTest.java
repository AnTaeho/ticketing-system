package com.example.ticketing.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.booking.support.RedisKeyCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

@BookingIntegrationTest
class SeatHoldGateRollbackTest {

    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;
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
            double gateOkBefore = meterRegistry.counter("booking.hold.gate", "result", "ok").count();
            double releaseFailureBefore = meterRegistry.counter("booking.redis.reflect.failure", "op", "release").count();

            assertThatThrownBy(() -> seatHoldService.hold(userId, pid, target, null))
                    .isInstanceOf(DataAccessResourceFailureException.class);

            assertThat(meterRegistry.counter("booking.hold.gate", "result", "ok").count() - gateOkBefore).isEqualTo(1);
            assertThat(meterRegistry.counter("booking.redis.reflect.failure", "op", "release").count())
                    .isEqualTo(releaseFailureBefore);
            assertThat(redisKeyCleaner.keys(pid)).isEmpty();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE performance_id = ?",
                    Long.class, pid)).isZero();
            assertThat(jdbcTemplate.queryForList("SELECT DISTINCT status FROM seat WHERE performance_id = ?",
                    String.class, pid)).containsExactly("AVAILABLE");
        } finally {
            redisKeyCleaner.clean(pid);
        }
    }
}
