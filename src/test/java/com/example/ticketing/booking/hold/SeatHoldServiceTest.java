package com.example.ticketing.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@BookingIntegrationTest
class SeatHoldServiceTest {

    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("선점하면 hold는 HELD, 좌석은 HELD이고 held_until = expires_at = DB 시각 + 5분")
    void holdWritesHoldAndSeats() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> ids = seatIds(pid);
        long userId = fixtures.newUser();
        LocalDateTime before = fixtures.dbNow();

        HoldCreated created = seatHoldService.hold(userId, pid, List.of(ids.get(1), ids.get(0)), "ignored");

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
        seatHoldService.hold(userId, pid, List.of(ids.get(0)), null);

        assertThatThrownBy(() -> seatHoldService.hold(userId, pid, List.of(ids.get(1)), null))
                .isInstanceOf(BookingException.class)
                .extracting("code").isEqualTo(BookingErrorCode.USER_HAS_HOLD);
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
        HoldCreated holdA = seatHoldService.hold(userA, pid, target, null);

        seatHoldService.release(userA, holdA.holdId());

        assertThat(holdStatus(holdA.holdId())).isEqualTo("RELEASED");
        List<Map<String, Object>> seats = jdbcTemplate.queryForList(
                "SELECT status, hold_id, held_until FROM seat WHERE id IN (?, ?)", target.get(0), target.get(1));
        assertThat(seats).allSatisfy(seat -> {
            assertThat(seat.get("status")).isEqualTo("AVAILABLE");
            assertThat(seat.get("hold_id")).isNull();
            assertThat(seat.get("held_until")).isNull();
        });

        HoldCreated holdB = seatHoldService.hold(userB, pid, target, null);
        assertThat(heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);

        assertThatThrownBy(() -> seatHoldService.release(userA, holdA.holdId()))
                .extracting("code").isEqualTo(BookingErrorCode.HOLD_NOT_CANCELLABLE);
        assertThatThrownBy(() -> seatHoldService.release(userA, holdB.holdId()))
                .extracting("code").isEqualTo(BookingErrorCode.HOLD_NOT_FOUND);
        assertThat(heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);
    }

    @Test
    @DisplayName("held_until이 지난 HELD 좌석은 다른 사용자가 가져가고 원래 hold는 그대로 남는다")
    void expiredSeatsCanBeTaken() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = seatIds(pid).subList(0, 2);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        HoldCreated holdA = seatHoldService.hold(userA, pid, target, null);
        long otherUser = fixtures.newUser();

        assertThatThrownBy(() -> seatHoldService.hold(otherUser, pid, target, null))
                .extracting("code").isEqualTo(BookingErrorCode.SEAT_STATE_CHANGED);

        jdbcTemplate.update("UPDATE seat SET held_until = NOW(6) - INTERVAL 1 SECOND "
                + "WHERE performance_id = ? AND hold_id = ?", pid, holdA.holdId());
        HoldCreated holdB = seatHoldService.hold(userB, pid, target, null);

        assertThat(heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);
        assertThat(heldSeats(pid, holdA.holdId())).isEmpty();
        assertThat(holdStatus(holdA.holdId())).isEqualTo("HELD");
    }

    @Test
    @DisplayName("예매가 열리기 전 회차는 NOT_OPEN이고 hold를 만들지 않는다")
    void notOpen() {
        LocalDateTime now = fixtures.dbNow();
        long pid = fixtures.newPerformance(fixtures.newShow(), now.plusMinutes(10), now.plusDays(1), 1, 1, 2);
        long userId = fixtures.newUser();

        assertThatThrownBy(() -> seatHoldService.hold(userId, pid, seatIds(pid), null))
                .extracting("code").isEqualTo(BookingErrorCode.NOT_OPEN);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE performance_id = ?",
                Long.class, pid)).isZero();
    }

    @Test
    @DisplayName("없는 사용자 ID로 선점하면 LOGIN_REQUIRED이고 hold를 만들지 않는다")
    void unknownUser() {
        long pid = fixtures.newPerformance(1, 1, 2);

        assertThatThrownBy(() -> seatHoldService.hold(Long.MAX_VALUE, pid, seatIds(pid), null))
                .extracting("code").isEqualTo(BookingErrorCode.LOGIN_REQUIRED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE performance_id = ?",
                Long.class, pid)).isZero();
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
