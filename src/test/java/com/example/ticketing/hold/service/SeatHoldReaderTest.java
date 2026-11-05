package com.example.ticketing.hold.service;

import static com.example.ticketing.support.ApiErrors.catchApiException;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.hold.dto.ActiveHoldResult;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.hold.dto.HoldDetailResult;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class SeatHoldReaderTest {

    @Autowired
    private SeatHoldReader seatHoldReader;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("남의 선점이나 없는 선점은 조회하면 HOLD_NOT_FOUND이고, 놓은 선점은 좌석 없이 RELEASED로 조회된다")
    void getHold() {
        long pid = fixtures.newPerformance(1, 1, 2);
        long owner = fixtures.newUser();
        long stranger = fixtures.newUser();
        long holdId = hold(owner, pid, List.of(db.seatIds(pid).get(0))).holdId();

        assertThat(catchApiException(() -> seatHoldReader.getHold(stranger, holdId)).getCode())
                .isEqualTo(ErrorCode.HOLD_NOT_FOUND);
        assertThat(catchApiException(() -> seatHoldReader.getHold(owner, Long.MAX_VALUE)).getCode())
                .isEqualTo(ErrorCode.HOLD_NOT_FOUND);
        assertThat(db.holdStatus(holdId)).isEqualTo("HELD");

        seatHoldService.release(owner, holdId);

        HoldDetailResult released = seatHoldReader.getHold(owner, holdId);
        assertThat(released.status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(released.seats()).isEmpty();
    }

    @Test
    @DisplayName("내 선점 조회는 그 회차의 내 HELD·PAYING 선점만 돌려주고 없으면 HOLD_NOT_FOUND다")
    void activeHold() {
        long pid = fixtures.newPerformance(1, 1, 4);
        long otherPid = fixtures.newPerformance(1, 1, 2);
        long owner = fixtures.newUser();
        long stranger = fixtures.newUser();
        assertThat(catchApiException(() -> seatHoldReader.getActiveHold(owner, pid)).getCode())
                .isEqualTo(ErrorCode.HOLD_NOT_FOUND);

        long holdId = hold(owner, pid, List.of(db.seatIds(pid).get(0))).holdId();

        LocalDateTime expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM seat_hold WHERE hold_id = ?", LocalDateTime.class, holdId);
        assertThat(seatHoldReader.getActiveHold(owner, pid))
                .isEqualTo(new ActiveHoldResult(holdId, HoldStatus.HELD, expiresAt));
        for (long[] userAndPid : new long[][]{{stranger, pid}, {owner, otherPid}}) {
            assertThat(catchApiException(() -> seatHoldReader.getActiveHold(userAndPid[0], userAndPid[1])).getCode())
                    .isEqualTo(ErrorCode.HOLD_NOT_FOUND);
        }

        seatHoldService.release(owner, holdId);
        assertThat(catchApiException(() -> seatHoldReader.getActiveHold(owner, pid)).getCode())
                .isEqualTo(ErrorCode.HOLD_NOT_FOUND);

        long paying = hold(owner, pid, List.of(db.seatIds(pid).get(1))).holdId();
        jdbcTemplate.update("UPDATE seat_hold SET status = 'PAYING' WHERE hold_id = ?", paying);
        assertThat(seatHoldReader.getActiveHold(owner, pid))
                .extracting(ActiveHoldResult::holdId, ActiveHoldResult::status)
                .containsExactly(paying, HoldStatus.PAYING);
    }

    private CreateHoldResult hold(long userId, long pid, List<Long> seatIds) {
        return seatHoldService.hold(new CreateHoldCommand(userId, pid, seatIds));
    }
}
