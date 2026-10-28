package com.example.ticketing.seat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.seat.domain.PerformanceInfo;
import com.example.ticketing.seat.domain.SeatGrade;
import com.example.ticketing.seat.domain.SeatInfo;
import com.example.ticketing.seat.dto.SeatSelection;
import com.example.ticketing.seat.repository.SeatJdbcRepository;
import com.example.ticketing.show.domain.Performance;
import com.example.ticketing.show.domain.PerformanceState;
import com.example.ticketing.show.repository.PerformanceRepository;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class SeatCatalogTest {

    @Autowired
    private SeatJdbcRepository seatJdbcRepository;
    @Autowired
    private SeatCatalog seatCatalog;
    @Autowired
    private PerformanceRepository performanceRepository;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TicketingProperties properties;

    @Test
    @DisplayName("캐시한 좌석의 가격·등급·위치와 회차 시각이 DB와 같다")
    void catalogMatchesDatabase() {
        long pid = fixtures.newPerformance(2, 10, 4);

        PerformanceInfo info = seatCatalog.performance(pid);

        Performance performance = performanceRepository.findById(pid).orElseThrow();
        assertThat(info.openAt()).isEqualTo(performance.getOpenAt());
        assertThat(info.startsAt()).isEqualTo(performance.getStartsAt());
        assertThat(info.gateCloseAt()).isEqualTo(performance.getGateCloseAt());
        assertThat(info.showId()).isEqualTo(performance.getShowId());

        List<SeatInfo> fromDb = jdbcTemplate.query(
                "SELECT id, section, row_no, seat_no, grade, price FROM seat WHERE performance_id = ?",
                (rs, i) -> new SeatInfo(rs.getLong("id"), rs.getString("section"), rs.getInt("row_no"),
                        rs.getInt("seat_no"), SeatGrade.valueOf(rs.getString("grade")), rs.getInt("price")),
                pid);
        assertThat(fromDb).hasSize(80);
        assertThat(info.seats().values()).containsExactlyInAnyOrderElementsOf(fromDb);
        assertThat(info.seats().values())
                .allSatisfy(seat -> assertThat(seat.price()).isEqualTo(seat.grade().getPrice()));
    }

    @Test
    @DisplayName("처음 조회 때 한 번 적재한 뒤에는 DB를 다시 읽지 않는다")
    void loadsOnce() {
        long pid = fixtures.newPerformance(1, 1, 2);
        List<Long> seatIds = seatIds(pid);
        seatCatalog.performance(pid);

        jdbcTemplate.update("DELETE FROM seat WHERE performance_id = ?", pid);

        assertThat(seatCatalog.validateSeats(pid, seatIds).seatIds()).isEqualTo(seatIds);
    }

    @Test
    @DisplayName("고른 좌석을 정렬해 돌려주고 총액을 계산한다")
    void validSelection() {
        long pid = fixtures.newPerformance(1, 10, 2);
        List<Long> ids = seatIds(pid);
        Long vip = ids.get(0);
        Long r = ids.get(4);
        Long a = ids.get(19);

        SeatSelection selection = seatCatalog.validateSeats(pid, List.of(a, vip, r));

        assertThat(selection.performanceId()).isEqualTo(pid);
        assertThat(selection.seatIds()).containsExactly(vip, r, a);
        assertThat(selection.seats()).extracting(SeatInfo::id).containsExactly(vip, r, a);
        assertThat(selection.seats()).extracting(SeatInfo::grade)
                .containsExactly(SeatGrade.VIP, SeatGrade.R, SeatGrade.A);
        assertThat(selection.totalAmount()).isEqualTo(180_000 + 150_000 + 90_000);
    }

    @Test
    @DisplayName("최대 매수까지는 고를 수 있다")
    void maxSeatsAllowed() {
        long pid = fixtures.newPerformance(1, 1, 6);
        int maxSeats = properties.hold().maxSeats();
        List<Long> ids = seatIds(pid).subList(0, maxSeats);

        SeatSelection selection = seatCatalog.validateSeats(pid, ids);

        assertThat(selection.seatIds()).hasSize(maxSeats);
        assertThat(selection.totalAmount()).isEqualTo(maxSeats * SeatGrade.VIP.getPrice());
    }

    @Test
    @DisplayName("0석, 최대 매수 초과, 중복, 다른 회차 좌석, 없는 좌석은 INVALID_SEAT_SELECTION")
    void invalidSelections() {
        long pid = fixtures.newPerformance(1, 1, 6);
        long otherPid = fixtures.newPerformance(1, 1, 2);
        List<Long> ids = seatIds(pid);
        Long otherSeat = seatIds(otherPid).get(0);

        assertInvalidSelection(pid, List.of());
        assertInvalidSelection(pid, null);
        assertInvalidSelection(pid, ids.subList(0, properties.hold().maxSeats() + 1));
        assertInvalidSelection(pid, List.of(ids.get(0), ids.get(0)));
        assertInvalidSelection(pid, List.of(ids.get(0), otherSeat));
        assertInvalidSelection(pid, List.of(ids.get(0), Long.MAX_VALUE));
        List<Long> withNull = new ArrayList<>();
        withNull.add(ids.get(0));
        withNull.add(null);
        assertInvalidSelection(pid, withNull);
    }

    @Test
    @DisplayName("없는 회차는 PERFORMANCE_NOT_FOUND")
    void unknownPerformance() {
        assertThatThrownBy(() -> seatCatalog.validateSeats(Long.MAX_VALUE, List.of(1L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.PERFORMANCE_NOT_FOUND);
    }

    @Test
    @DisplayName("예매 중인 회차 목록은 open_at <= 지금 < starts_at인 회차만 담고 10초 동안 다시 읽지 않는다")
    void openPerformanceIds() {
        SeatCatalog fresh = new SeatCatalog(seatJdbcRepository, properties, System::nanoTime);
        long showId = fixtures.newShow();
        LocalDateTime now = fixtures.dbNow();
        long open = fixtures.newPerformance(showId, now.minusMinutes(1), now.plusDays(1), 1, 1, 1);
        long beforeOpen = fixtures.newPerformance(showId, now.plusHours(1), now.plusDays(1), 1, 1, 1);
        long closed = fixtures.newPerformance(showId, now.minusDays(2), now.minusMinutes(1), 1, 1, 1);

        List<Long> first = fresh.openPerformanceIds();
        long openedLater = fixtures.newPerformance(showId, now.minusMinutes(1), now.plusDays(1), 1, 1, 1);
        List<Long> second = fresh.openPerformanceIds();

        assertThat(first).contains(open).doesNotContain(beforeOpen, closed);
        assertThat(second).isEqualTo(first).doesNotContain(openedLater);
    }

    @Test
    @DisplayName("requireOpen은 넘긴 시각이 open_at 이상 starts_at 미만일 때만 회차를 돌려주고 그 밖은 409 NOT_OPEN이다")
    void requireOpenUsesGivenTime() {
        long pid = fixtures.newPerformance(1, 1, 1);
        PerformanceInfo info = seatCatalog.performance(pid);

        assertThat(seatCatalog.requireOpen(pid, info.openAt())).isSameAs(info);
        assertThat(seatCatalog.requireOpen(pid, info.startsAt().minusNanos(1000))).isSameAs(info);
        assertNotOpen(pid, info.openAt().minusNanos(1000));
        assertNotOpen(pid, info.startsAt());
        assertThat(info.stateAt(info.openAt().minusNanos(1000))).isEqualTo(PerformanceState.BEFORE_OPEN);
        assertThat(info.stateAt(info.startsAt())).isEqualTo(PerformanceState.CLOSED);
    }

    private void assertNotOpen(long pid, LocalDateTime now) {
        assertThatThrownBy(() -> seatCatalog.requireOpen(pid, now))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.NOT_OPEN);
    }

    private List<Long> seatIds(long pid) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM seat WHERE performance_id = ? ORDER BY row_no, section, seat_no", Long.class, pid);
    }

    private void assertInvalidSelection(long pid, Collection<Long> seatIds) {
        assertThatThrownBy(() -> seatCatalog.validateSeats(pid, seatIds))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_SEAT_SELECTION);
    }
}
