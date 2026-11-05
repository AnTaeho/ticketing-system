package com.example.ticketing.hold.service;

import static com.example.ticketing.support.ApiErrors.catchApiException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.hold.dto.ActiveHoldResult;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.hold.dto.HoldDetailResult;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestRedis;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class SeatHoldServiceTest {

    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private TestRedis testRedis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("선점하면 선점과 좌석이 DB 시각 기준 5분 뒤까지 잡힌다")
    void holdWritesHoldAndSeats() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> ids = db.seatIds(pid);
        long userId = fixtures.newUser();
        LocalDateTime before = fixtures.dbNow();

        CreateHoldResult created = seatHoldService.hold(
                new CreateHoldCommand(userId, pid, List.of(ids.get(1), ids.get(0))));

        Map<String, Object> hold = jdbcTemplate.queryForMap("SELECT * FROM seat_hold WHERE hold_id = ?", created.holdId());
        assertThat(hold.get("status")).isEqualTo("HELD");
        assertThat(hold.get("user_id")).isEqualTo(userId);
        assertThat(hold.get("performance_id")).isEqualTo(pid);
        assertThat(hold.get("seat_count")).isEqualTo(2);
        assertThat(((String) hold.get("hold_token"))).hasSize(36);
        assertThat(created.expiresAt()).isAfter(before.plusMinutes(5).minusSeconds(1))
                .isBefore(before.plusMinutes(5).plusSeconds(5));
        assertThat(created.seats()).extracting(s -> s.id()).containsExactly(ids.get(0), ids.get(1));
        assertThat(db.heldSeats(pid, created.holdId())).containsExactly(ids.get(0), ids.get(1));
        assertThat(jdbcTemplate.queryForList("SELECT held_until FROM seat WHERE performance_id = ? AND hold_id = ?",
                LocalDateTime.class, pid, created.holdId())).containsOnly(created.expiresAt());
    }

    @Test
    @DisplayName("같은 사용자가 두 번째로 잡으면 USER_HAS_HOLD로 거절한다")
    void secondHoldRejected() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> ids = db.seatIds(pid);
        long userId = fixtures.newUser();
        seatHoldService.hold(new CreateHoldCommand(userId, pid, List.of(ids.get(0))));

        assertThatThrownBy(() -> seatHoldService.hold(
                new CreateHoldCommand(userId, pid, List.of(ids.get(1)))))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.USER_HAS_HOLD);
        assertThat(db.seatStatus(ids.get(1))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("선점을 취소하면 좌석이 풀려 다른 사용자가 같은 좌석을 잡을 수 있다")
    void releaseThenOtherUserHolds() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = db.seatIds(pid).subList(0, 2);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        CreateHoldResult holdA = seatHoldService.hold(
                new CreateHoldCommand(userA, pid, target));

        seatHoldService.release(userA, holdA.holdId());

        assertThat(db.holdStatus(holdA.holdId())).isEqualTo("RELEASED");
        List<Map<String, Object>> seats = jdbcTemplate.queryForList(
                "SELECT status, hold_id, held_until FROM seat WHERE seat_id IN (?, ?)", target.get(0), target.get(1));
        assertThat(seats).allSatisfy(seat -> {
            assertThat(seat.get("status")).isEqualTo("AVAILABLE");
            assertThat(seat.get("hold_id")).isNull();
            assertThat(seat.get("held_until")).isNull();
        });

        CreateHoldResult holdB = seatHoldService.hold(
                new CreateHoldCommand(userB, pid, target));
        assertThat(db.heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);

        assertThatThrownBy(() -> seatHoldService.release(userA, holdA.holdId()))
                .extracting("code").isEqualTo(ErrorCode.HOLD_NOT_CANCELLABLE);
        assertThatThrownBy(() -> seatHoldService.release(userA, holdB.holdId()))
                .extracting("code").isEqualTo(ErrorCode.HOLD_NOT_FOUND);
        assertThat(db.heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);
    }

    @Test
    @DisplayName("기한이 지난 좌석은 다른 사용자가 가져가고 원래 선점은 그대로 남는다")
    void expiredSeatsCanBeTaken() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = db.seatIds(pid).subList(0, 2);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        CreateHoldResult holdA = seatHoldService.hold(
                new CreateHoldCommand(userA, pid, target));
        long otherUser = fixtures.newUser();

        assertThatThrownBy(() -> seatHoldService.hold(new CreateHoldCommand(otherUser, pid, target)))
                .extracting("code").isEqualTo(ErrorCode.SEAT_TAKEN);

        // 게이트 키는 선점과 같은 TTL이라 held_until이 지나면 함께 사라진다. DB 시각만 당겼으므로 키도 지운다
        jdbcTemplate.update("UPDATE seat SET held_until = NOW(6) - INTERVAL 1 SECOND "
                + "WHERE performance_id = ? AND hold_id = ?", pid, holdA.holdId());
        testRedis.clean(pid);
        CreateHoldResult holdB = seatHoldService.hold(
                new CreateHoldCommand(userB, pid, target));

        assertThat(db.heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);
        assertThat(db.heldSeats(pid, holdA.holdId())).isEmpty();
        assertThat(db.holdStatus(holdA.holdId())).isEqualTo("HELD");
    }

    @Test
    @DisplayName("예매가 열리기 전 회차를 잡으면 NOT_OPEN이고 선점을 만들지 않는다")
    void notOpen() {
        LocalDateTime now = fixtures.dbNow();
        long pid = fixtures.newPerformance(fixtures.newShow(), now.plusMinutes(10), now.plusDays(1), 1, 1, 2);
        long userId = fixtures.newUser();

        assertThatThrownBy(() -> seatHoldService.hold(new CreateHoldCommand(userId, pid, db.seatIds(pid))))
                .extracting("code").isEqualTo(ErrorCode.NOT_OPEN);
        assertThat(db.holdCount(pid)).isZero();
    }

    @Test
    @DisplayName("없는 사용자 ID로 잡으면 LOGIN_REQUIRED이고 선점을 만들지 않는다")
    void unknownUser() {
        long pid = fixtures.newPerformance(1, 1, 2);

        assertThatThrownBy(() -> seatHoldService.hold(
                new CreateHoldCommand(Long.MAX_VALUE, pid, db.seatIds(pid))))
                .extracting("code").isEqualTo(ErrorCode.LOGIN_REQUIRED);
        assertThat(db.holdCount(pid)).isZero();
    }

    @Test
    @DisplayName("남이 잡은 좌석이 섞이면 SEAT_TAKEN으로 그 좌석을 알려 주고 나머지 좌석도 잡지 않는다")
    void seatTakenNamesSeat() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> ids = db.seatIds(pid);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        hold(userA, pid, List.of(ids.get(0)));

        ApiException e = catchApiException(() -> hold(userB, pid, List.of(ids.get(0), ids.get(1))));

        assertThat(e.getCode()).isEqualTo(ErrorCode.SEAT_TAKEN);
        assertThat(e.getDetail()).containsEntry("seatId", ids.get(0));
        assertThat(db.seatStatus(ids.get(1))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("고른 좌석이 잘못됐으면 INVALID_SEAT_SELECTION이고 선점을 만들지 않는다")
    void invalidSelectionDoesNotReachDb() {
        long pid = fixtures.newPerformance(1, 1, 6);
        long otherPid = fixtures.newPerformance(1, 1, 2);
        List<Long> ids = db.seatIds(pid);
        long userId = fixtures.newUser();

        for (List<Long> invalid : List.of(List.of(ids.get(0), db.seatIds(otherPid).get(0)),
                List.of(ids.get(0), Long.MAX_VALUE), ids.subList(0, 5))) {
            assertThat(catchApiException(() -> hold(userId, pid, invalid)).getCode())
                    .isEqualTo(ErrorCode.INVALID_SEAT_SELECTION);
        }

        assertThat(db.holdCount(pid)).isZero();
    }

    @Test
    @DisplayName("진행 중이 아닌 선점이나 결제 중인 선점을 놓으면 HOLD_NOT_CANCELLABLE로 그 상태를 알려 주고 좌석을 그대로 둔다")
    void notCancellableCarriesStatus() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<String> states = List.of("PAYING", "EXPIRED", "CONFIRMED", "RELEASED");
        for (int i = 0; i < states.size(); i++) {
            String state = states.get(i);
            long seatId = db.seatIds(pid).get(i);
            long userId = fixtures.newUser();
            long holdId = hold(userId, pid, List.of(seatId)).holdId();
            jdbcTemplate.update("UPDATE seat_hold SET status = ? WHERE hold_id = ?", state, holdId);

            ApiException e = catchApiException(() -> seatHoldService.release(userId, holdId));

            assertThat(e.getCode()).as(state).isEqualTo(ErrorCode.HOLD_NOT_CANCELLABLE);
            assertThat(e.getDetail()).as(state).containsEntry("status", state);
            assertThat(db.holdStatus(holdId)).isEqualTo(state);
            assertThat(db.seatStatus(seatId)).as(state).isEqualTo("HELD");
        }
    }

    @Test
    @DisplayName("남의 선점이나 없는 선점은 조회·놓기 모두 HOLD_NOT_FOUND이고, 놓은 선점은 좌석 없이 RELEASED로 조회된다")
    void getHold() {
        long pid = fixtures.newPerformance(1, 1, 2);
        long owner = fixtures.newUser();
        long stranger = fixtures.newUser();
        long holdId = hold(owner, pid, List.of(db.seatIds(pid).get(0))).holdId();

        assertThat(catchApiException(() -> seatHoldService.getHold(stranger, holdId)).getCode())
                .isEqualTo(ErrorCode.HOLD_NOT_FOUND);
        assertThat(catchApiException(() -> seatHoldService.release(stranger, holdId)).getCode())
                .isEqualTo(ErrorCode.HOLD_NOT_FOUND);
        assertThat(catchApiException(() -> seatHoldService.getHold(owner, Long.MAX_VALUE)).getCode())
                .isEqualTo(ErrorCode.HOLD_NOT_FOUND);
        assertThat(db.holdStatus(holdId)).isEqualTo("HELD");

        seatHoldService.release(owner, holdId);

        HoldDetailResult released = seatHoldService.getHold(owner, holdId);
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
        assertThat(catchApiException(() -> seatHoldService.getActiveHold(owner, pid)).getCode())
                .isEqualTo(ErrorCode.HOLD_NOT_FOUND);

        long holdId = hold(owner, pid, List.of(db.seatIds(pid).get(0))).holdId();

        LocalDateTime expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM seat_hold WHERE hold_id = ?", LocalDateTime.class, holdId);
        assertThat(seatHoldService.getActiveHold(owner, pid))
                .isEqualTo(new ActiveHoldResult(holdId, HoldStatus.HELD, expiresAt));
        for (long[] userAndPid : new long[][]{{stranger, pid}, {owner, otherPid}}) {
            assertThat(catchApiException(() -> seatHoldService.getActiveHold(userAndPid[0], userAndPid[1])).getCode())
                    .isEqualTo(ErrorCode.HOLD_NOT_FOUND);
        }

        seatHoldService.release(owner, holdId);
        assertThat(catchApiException(() -> seatHoldService.getActiveHold(owner, pid)).getCode())
                .isEqualTo(ErrorCode.HOLD_NOT_FOUND);

        long paying = hold(owner, pid, List.of(db.seatIds(pid).get(1))).holdId();
        jdbcTemplate.update("UPDATE seat_hold SET status = 'PAYING' WHERE hold_id = ?", paying);
        assertThat(seatHoldService.getActiveHold(owner, pid))
                .extracting(ActiveHoldResult::holdId, ActiveHoldResult::status)
                .containsExactly(paying, HoldStatus.PAYING);
    }

    private CreateHoldResult hold(long userId, long pid, List<Long> seatIds) {
        return seatHoldService.hold(new CreateHoldCommand(userId, pid, seatIds));
    }
}
