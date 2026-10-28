package com.example.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.common.time.TestDbClock;
import com.example.ticketing.hold.domain.GateResult;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.hold.dto.HoldPlacement;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.seat.dto.SeatSelection;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestProperties;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.time.Duration;
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
    private TestServices services;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TicketingProperties properties;

    @Test
    @DisplayName("선점하면 선점과 좌석이 DB 시각 기준 5분 뒤까지 잡힌다")
    void holdWritesHoldAndSeats() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> ids = db.seatIds(pid);
        long userId = fixtures.newUser();
        LocalDateTime before = fixtures.dbNow();

        CreateHoldResult created = seatHoldService.hold(
                new CreateHoldCommand(userId, pid, List.of(ids.get(1), ids.get(0)),
                fixtures.admit(pid, userId)));

        Map<String, Object> hold = jdbcTemplate.queryForMap("SELECT * FROM seat_hold WHERE id = ?", created.holdId());
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
        seatHoldService.hold(new CreateHoldCommand(userId, pid, List.of(ids.get(0)), fixtures.admit(pid, userId)));

        String queueToken = fixtures.admit(pid, userId);
        assertThatThrownBy(() -> seatHoldService.hold(
                new CreateHoldCommand(userId, pid, List.of(ids.get(1)), queueToken)))
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
                new CreateHoldCommand(userA, pid, target, fixtures.admit(pid, userA)));

        seatHoldService.release(userA, holdA.holdId());

        assertThat(db.holdStatus(holdA.holdId())).isEqualTo("RELEASED");
        List<Map<String, Object>> seats = jdbcTemplate.queryForList(
                "SELECT status, hold_id, held_until FROM seat WHERE id IN (?, ?)", target.get(0), target.get(1));
        assertThat(seats).allSatisfy(seat -> {
            assertThat(seat.get("status")).isEqualTo("AVAILABLE");
            assertThat(seat.get("hold_id")).isNull();
            assertThat(seat.get("held_until")).isNull();
        });

        CreateHoldResult holdB = seatHoldService.hold(
                new CreateHoldCommand(userB, pid, target, fixtures.admit(pid, userB)));
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
                new CreateHoldCommand(userA, pid, target, fixtures.admit(pid, userA)));
        long otherUser = fixtures.newUser();

        String queueToken = fixtures.admit(pid, otherUser);
        assertThatThrownBy(() -> seatHoldService.hold(new CreateHoldCommand(otherUser, pid, target, queueToken)))
                .extracting("code").isEqualTo(ErrorCode.SEAT_TAKEN);

        // 게이트 키는 선점과 같은 TTL이라 held_until이 지나면 함께 사라진다. DB 시각만 당겼으므로 키도 지운다
        jdbcTemplate.update("UPDATE seat SET held_until = NOW(6) - INTERVAL 1 SECOND "
                + "WHERE performance_id = ? AND hold_id = ?", pid, holdA.holdId());
        testRedis.clean(pid);
        CreateHoldResult holdB = seatHoldService.hold(
                new CreateHoldCommand(userB, pid, target, fixtures.admit(pid, userB)));

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

        // 열리기 전에는 줄도 설 수 없다. 회차 검증이 처리열 확인보다 먼저라 토큰 없이도 NOT_OPEN이다
        assertThatThrownBy(() -> seatHoldService.hold(new CreateHoldCommand(userId, pid, db.seatIds(pid), null)))
                .extracting("code").isEqualTo(ErrorCode.NOT_OPEN);
        assertThat(db.holdCount(pid)).isZero();
    }

    @Test
    @DisplayName("없는 사용자 ID로 잡으면 LOGIN_REQUIRED이고 선점을 만들지 않는다")
    void unknownUser() {
        long pid = fixtures.newPerformance(1, 1, 2);
        String token = fixtures.admit(pid, Long.MAX_VALUE);

        assertThatThrownBy(() -> seatHoldService.hold(
                new CreateHoldCommand(Long.MAX_VALUE, pid, db.seatIds(pid), token)))
                .extracting("code").isEqualTo(ErrorCode.LOGIN_REQUIRED);
        assertThat(db.holdCount(pid)).isZero();
    }

    @Test
    @DisplayName("사전 오픈 판정은 DB 시각을 기준으로 열림 여부를 가린다")
    void openCheckUsesDbTime() {
        LocalDateTime now = fixtures.dbNow();
        long show = fixtures.newShow();
        long opensInAnHour = fixtures.newPerformance(show, now.plusHours(1), now.plusDays(1), 1, 1, 2);
        long openedAnHourAgo = fixtures.newPerformance(show, now.minusHours(1), now.plusDays(1), 1, 1, 2);
        SeatHoldTransaction tx = mock(SeatHoldTransaction.class);
        when(tx.persistHold(anyLong(), any(SeatSelection.class), anyString()))
                .thenReturn(new HoldPlacement(1L, "t", now.plusMinutes(5)));
        SeatHoldService dbAhead = serviceWith(TestDbClock.withDbShift(jdbcTemplate, Duration.ofHours(2)), tx);
        SeatHoldService dbBehind = serviceWith(TestDbClock.withDbShift(jdbcTemplate, Duration.ofHours(-2)), tx);

        assertThat(dbAhead.hold(
                new CreateHoldCommand(1L, opensInAnHour, db.seatIds(opensInAnHour).subList(0, 1), null)).holdId())
                .isEqualTo(1L);
        verify(tx).persistHold(anyLong(), any(SeatSelection.class), anyString());

        List<Long> openedSeats = db.seatIds(openedAnHourAgo).subList(0, 1);
        assertThatThrownBy(() -> dbBehind.hold(new CreateHoldCommand(2L, openedAnHourAgo, openedSeats, null)))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.NOT_OPEN);
        // 두 번째 회차는 사전 판정에서 막혀 DB 판정 호출 수가 그대로다
        verify(tx, times(1)).persistHold(anyLong(), any(SeatSelection.class), anyString());
    }

    // 처리열·게이트는 통과시키고 DB 판정은 목으로 받아 사전 판정만 본다
    private SeatHoldService serviceWith(DbClock clock, SeatHoldTransaction tx) {
        TicketingProperties noQueue = TestProperties.from(properties).queueRequired(false).build();
        SeatGateRedisStore openGate = mock(SeatGateRedisStore.class);
        when(openGate.tryHold(any(), any())).thenReturn(GateResult.ok());
        return services.seatHoldService().properties(noQueue).gateStore(openGate).transaction(tx).dbClock(clock)
                .build();
    }
}
