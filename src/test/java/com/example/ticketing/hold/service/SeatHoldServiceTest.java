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
import com.example.ticketing.common.config.TicketingProperties.Queue;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.common.time.TestDbClock;
import com.example.ticketing.hold.domain.GateResult;
import com.example.ticketing.hold.dto.HoldCreated;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.hold.service.SeatHoldTransaction.PersistedHold;
import com.example.ticketing.payment.repository.PaymentRepository;
import com.example.ticketing.queue.service.QueueService;
import com.example.ticketing.seat.repository.SeatRepository;
import com.example.ticketing.seat.service.SeatCatalog;
import com.example.ticketing.seat.service.SeatCatalog.SeatSelection;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.RedisKeyCleaner;
import io.micrometer.core.instrument.MeterRegistry;
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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;
    @Autowired
    private SeatCatalog seatCatalog;
    @Autowired
    private SeatHoldRepository seatHoldRepository;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private QueueService queueService;
    @Autowired
    private TicketingProperties properties;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("선점하면 hold는 HELD, 좌석은 HELD이고 held_until = expires_at = DB 시각 + 5분")
    void holdWritesHoldAndSeats() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> ids = seatIds(pid);
        long userId = fixtures.newUser();
        LocalDateTime before = fixtures.dbNow();

        HoldCreated created = seatHoldService.hold(userId, pid, List.of(ids.get(1), ids.get(0)),
                fixtures.admit(pid, userId));

        Map<String, Object> hold = jdbcTemplate.queryForMap("SELECT * FROM seat_hold WHERE id = ?", created.holdId());
        assertThat(hold.get("status")).isEqualTo("HELD");
        assertThat(hold.get("user_id")).isEqualTo(userId);
        assertThat(hold.get("performance_id")).isEqualTo(pid);
        assertThat(hold.get("seat_count")).isEqualTo(2);
        assertThat(((String) hold.get("hold_token"))).hasSize(36);
        assertThat(created.expiresAt()).isAfter(before.plusMinutes(5).minusSeconds(1))
                .isBefore(before.plusMinutes(5).plusSeconds(5));
        assertThat(created.seats()).extracting(s -> s.id()).containsExactly(ids.get(0), ids.get(1));
        assertThat(heldSeats(pid, created.holdId())).containsExactly(ids.get(0), ids.get(1));
        assertThat(jdbcTemplate.queryForList("SELECT held_until FROM seat WHERE performance_id = ? AND hold_id = ?",
                LocalDateTime.class, pid, created.holdId())).containsOnly(created.expiresAt());
    }

    @Test
    @DisplayName("같은 사용자의 두 번째 선점은 USER_HAS_HOLD")
    void secondHoldRejected() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> ids = seatIds(pid);
        long userId = fixtures.newUser();
        seatHoldService.hold(userId, pid, List.of(ids.get(0)), fixtures.admit(pid, userId));

        String queueToken = fixtures.admit(pid, userId);
        assertThatThrownBy(() -> seatHoldService.hold(userId, pid, List.of(ids.get(1)), queueToken))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.USER_HAS_HOLD);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM seat WHERE id = ?", String.class, ids.get(1)))
                .isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("선점을 취소하면 hold RELEASED, 좌석 AVAILABLE이고 다른 사용자가 같은 좌석을 잡을 수 있다")
    void releaseThenOtherUserHolds() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = seatIds(pid).subList(0, 2);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        HoldCreated holdA = seatHoldService.hold(userA, pid, target, fixtures.admit(pid, userA));

        seatHoldService.release(userA, holdA.holdId());

        assertThat(holdStatus(holdA.holdId())).isEqualTo("RELEASED");
        List<Map<String, Object>> seats = jdbcTemplate.queryForList(
                "SELECT status, hold_id, held_until FROM seat WHERE id IN (?, ?)", target.get(0), target.get(1));
        assertThat(seats).allSatisfy(seat -> {
            assertThat(seat.get("status")).isEqualTo("AVAILABLE");
            assertThat(seat.get("hold_id")).isNull();
            assertThat(seat.get("held_until")).isNull();
        });

        HoldCreated holdB = seatHoldService.hold(userB, pid, target, fixtures.admit(pid, userB));
        assertThat(heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);

        assertThatThrownBy(() -> seatHoldService.release(userA, holdA.holdId()))
                .extracting("code").isEqualTo(ErrorCode.HOLD_NOT_CANCELLABLE);
        assertThatThrownBy(() -> seatHoldService.release(userA, holdB.holdId()))
                .extracting("code").isEqualTo(ErrorCode.HOLD_NOT_FOUND);
        assertThat(heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);
    }

    @Test
    @DisplayName("held_until이 지난 HELD 좌석은 다른 사용자가 가져가고 원래 hold는 그대로 남는다")
    void expiredSeatsCanBeTaken() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = seatIds(pid).subList(0, 2);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        HoldCreated holdA = seatHoldService.hold(userA, pid, target, fixtures.admit(pid, userA));
        long otherUser = fixtures.newUser();

        String queueToken = fixtures.admit(pid, otherUser);
        assertThatThrownBy(() -> seatHoldService.hold(otherUser, pid, target, queueToken))
                .extracting("code").isEqualTo(ErrorCode.SEAT_TAKEN);

        // 게이트 키는 선점과 같은 TTL이라 held_until이 지나면 함께 사라진다. DB 시각만 당겼으므로 키도 지운다
        jdbcTemplate.update("UPDATE seat SET held_until = NOW(6) - INTERVAL 1 SECOND "
                + "WHERE performance_id = ? AND hold_id = ?", pid, holdA.holdId());
        redisKeyCleaner.clean(pid);
        HoldCreated holdB = seatHoldService.hold(userB, pid, target, fixtures.admit(pid, userB));

        assertThat(heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);
        assertThat(heldSeats(pid, holdA.holdId())).isEmpty();
        assertThat(holdStatus(holdA.holdId())).isEqualTo("HELD");
        redisKeyCleaner.clean(pid);
    }

    @Test
    @DisplayName("예매가 열리기 전 회차는 NOT_OPEN이고 hold를 만들지 않는다")
    void notOpen() {
        LocalDateTime now = fixtures.dbNow();
        long pid = fixtures.newPerformance(fixtures.newShow(), now.plusMinutes(10), now.plusDays(1), 1, 1, 2);
        long userId = fixtures.newUser();

        // 열리기 전에는 줄도 설 수 없다. 회차 검증이 처리열 확인보다 먼저라 토큰 없이도 NOT_OPEN이다
        assertThatThrownBy(() -> seatHoldService.hold(userId, pid, seatIds(pid), null))
                .extracting("code").isEqualTo(ErrorCode.NOT_OPEN);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE performance_id = ?",
                Long.class, pid)).isZero();
    }

    @Test
    @DisplayName("없는 사용자 ID로 선점하면 LOGIN_REQUIRED이고 hold를 만들지 않는다")
    void unknownUser() {
        long pid = fixtures.newPerformance(1, 1, 2);
        String token = fixtures.admit(pid, Long.MAX_VALUE);

        assertThatThrownBy(() -> seatHoldService.hold(Long.MAX_VALUE, pid, seatIds(pid), token))
                .extracting("code").isEqualTo(ErrorCode.LOGIN_REQUIRED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE performance_id = ?",
                Long.class, pid)).isZero();
    }

    @Test
    @DisplayName("사전 오픈 판정은 DB 시각 기준: DB가 2시간 앞서면 1시간 뒤 오픈 회차도 DB 판정까지 가고, 2시간 뒤지면 열린 회차도 409 NOT_OPEN")
    void openCheckUsesDbTime() {
        LocalDateTime now = fixtures.dbNow();
        long show = fixtures.newShow();
        long opensInAnHour = fixtures.newPerformance(show, now.plusHours(1), now.plusDays(1), 1, 1, 2);
        long openedAnHourAgo = fixtures.newPerformance(show, now.minusHours(1), now.plusDays(1), 1, 1, 2);
        SeatHoldTransaction tx = mock(SeatHoldTransaction.class);
        when(tx.persistHold(anyLong(), any(SeatSelection.class), anyString()))
                .thenReturn(new PersistedHold(1L, "t", now.plusMinutes(5)));
        SeatHoldService dbAhead = serviceWith(TestDbClock.withDbShift(jdbcTemplate, Duration.ofHours(2)), tx);
        SeatHoldService dbBehind = serviceWith(TestDbClock.withDbShift(jdbcTemplate, Duration.ofHours(-2)), tx);

        assertThat(dbAhead.hold(1L, opensInAnHour, seatIds(opensInAnHour).subList(0, 1), null).holdId())
                .isEqualTo(1L);
        verify(tx).persistHold(anyLong(), any(SeatSelection.class), anyString());

        List<Long> openedSeats = seatIds(openedAnHourAgo).subList(0, 1);
        assertThatThrownBy(() -> dbBehind.hold(2L, openedAnHourAgo, openedSeats, null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.NOT_OPEN);
        // 두 번째 회차는 사전 판정에서 막혀 DB 판정 호출 수가 그대로다
        verify(tx, times(1)).persistHold(anyLong(), any(SeatSelection.class), anyString());
    }

    // 처리열·게이트는 통과시키고 DB 판정은 목으로 받아 사전 판정만 본다
    private SeatHoldService serviceWith(DbClock clock, SeatHoldTransaction tx) {
        TicketingProperties p = properties;
        Queue q = p.queue();
        TicketingProperties noQueue = new TicketingProperties(p.seed(), p.redis(), p.hold(), p.payment(), p.gate(),
                new Queue(q.capacity(), q.processingTokenTtl(), q.waitingTokenTtl(), q.promoteEnabled(),
                        q.promoteIntervalMs(), false),
                p.seatmap(), p.outbox(), p.ops(), p.pg(), p.fakePg());
        SeatGateRedisStore openGate = mock(SeatGateRedisStore.class);
        when(openGate.tryHold(anyLong(), anyLong(), any(), anyString(), any())).thenReturn(GateResult.ok());
        return new SeatHoldService(seatCatalog, tx, seatHoldRepository, seatRepository, paymentRepository, clock,
                openGate, queueService, noQueue, meterRegistry);
    }

    private String holdStatus(long holdId) {
        return jdbcTemplate.queryForObject("SELECT status FROM seat_hold WHERE id = ?", String.class, holdId);
    }

    private List<Long> heldSeats(long pid, long holdId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM seat WHERE performance_id = ? AND hold_id = ? AND status = 'HELD' ORDER BY id",
                Long.class, pid, holdId);
    }

    private List<Long> seatIds(long pid) {
        return jdbcTemplate.queryForList("SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, pid);
    }
}
