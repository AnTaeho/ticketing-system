package com.example.ticketing.show.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.seat.domain.SeatGrade;
import com.example.ticketing.seat.domain.SeatLayout.SeatSpec;
import com.example.ticketing.seat.repository.SeatBulkInserter;
import com.example.ticketing.show.domain.Performance;
import com.example.ticketing.show.dto.CreatePerformanceCommand;
import com.example.ticketing.show.dto.CreatePerformanceResult;
import com.example.ticketing.show.repository.PerformanceRepository;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class ShowAdminServiceTest {

    @Autowired
    private ShowAdminService showAdminService;
    @Autowired
    private SeatBulkInserter seatBulkInserter;
    @Autowired
    private PerformanceRepository performanceRepository;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("4×10×20 회차를 만들면 좌석 800행이 생기고 등급별 개수가 규칙과 같다")
    void createPerformanceInsertsSeats() {
        long showId = fixtures.newShow();
        LocalDateTime now = fixtures.dbNow();

        long started = System.nanoTime();
        CreatePerformanceResult created = showAdminService.createPerformance(
                new CreatePerformanceCommand(showId, now.plusDays(1), now.minusMinutes(1), null, 4, 10, 20));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[S1.2] 회차 1건 + 좌석 800행 생성: %d ms%n", elapsedMs);

        assertThat(created.seatCount()).isEqualTo(800);
        assertThat(count("SELECT COUNT(*) FROM seat WHERE performance_id = ?", created.performanceId()))
                .isEqualTo(800);
        assertThat(count("SELECT COUNT(*) FROM seat WHERE performance_id = ? AND status = 'AVAILABLE'"
                + " AND hold_id IS NULL AND held_until IS NULL", created.performanceId()))
                .isEqualTo(800);

        Map<String, Integer> byGrade = new HashMap<>();
        jdbcTemplate.query("SELECT grade, COUNT(*) AS cnt, MIN(price) AS min_price, MAX(price) AS max_price"
                        + " FROM seat WHERE performance_id = ? GROUP BY grade",
                rs -> {
                    SeatGrade grade = SeatGrade.valueOf(rs.getString("grade"));
                    assertThat(rs.getInt("min_price")).isEqualTo(grade.getPrice());
                    assertThat(rs.getInt("max_price")).isEqualTo(grade.getPrice());
                    byGrade.put(grade.name(), rs.getInt("cnt"));
                },
                created.performanceId());
        assertThat(byGrade).containsExactlyInAnyOrderEntriesOf(Map.of("VIP", 160, "R", 240, "S", 240, "A", 160));
    }

    @Test
    @DisplayName("같은 위치 좌석을 다시 INSERT하면 uk_seat_position 유니크 위반")
    void duplicatePositionViolatesUniqueKey() {
        long pid = fixtures.newPerformance(1, 2, 3);
        SeatSpec same = new SeatSpec("A", 1, 1, SeatGrade.VIP, SeatGrade.VIP.getPrice());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> seatBulkInserter.insert(pid, List.of(same))))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_seat_position");
        assertThat(count("SELECT COUNT(*) FROM seat WHERE performance_id = ?", pid)).isEqualTo(6);
    }

    @Test
    @DisplayName("좌석 일괄 INSERT는 호출자 트랜잭션 없이 부를 수 없다")
    void bulkInsertRequiresTransaction() {
        assertThatThrownBy(() -> seatBulkInserter.insert(Long.MAX_VALUE,
                List.of(new SeatSpec("A", 1, 1, SeatGrade.VIP, SeatGrade.VIP.getPrice()))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("gateCloseAt이 없으면 openAt + 24시간")
    void gateCloseAtDefaultsToOpenPlus24h() {
        LocalDateTime openAt = fixtures.dbNow().plusHours(1);
        Performance performance = create(openAt, openAt.plusDays(3), null);

        assertThat(performance.getGateCloseAt()).isEqualTo(openAt.plusHours(24));
    }

    @Test
    @DisplayName("기본값 openAt + 24시간이 startsAt보다 늦으면 startsAt으로 맞춘다")
    void gateCloseAtDefaultClampedToStartsAt() {
        LocalDateTime openAt = fixtures.dbNow().plusHours(1);
        Performance performance = create(openAt, openAt.plusHours(2), null);

        assertThat(performance.getGateCloseAt()).isEqualTo(openAt.plusHours(2));
    }

    @Test
    @DisplayName("지정한 gateCloseAt은 그대로 쓰되 startsAt보다 늦으면 startsAt으로 맞춘다")
    void explicitGateCloseAt() {
        LocalDateTime openAt = fixtures.dbNow().plusHours(1);

        assertThat(create(openAt, openAt.plusDays(3), openAt.plusHours(5)).getGateCloseAt())
                .isEqualTo(openAt.plusHours(5));
        assertThat(create(openAt, openAt.plusHours(3), openAt.plusHours(10)).getGateCloseAt())
                .isEqualTo(openAt.plusHours(3));
    }

    @Test
    @DisplayName("openAt이 startsAt보다 앞서지 않으면 INVALID_REQUEST")
    void openAtMustPrecedeStartsAt() {
        long showId = fixtures.newShow();
        LocalDateTime now = fixtures.dbNow();

        assertInvalid(new CreatePerformanceCommand(showId, now, now, null, 1, 1, 1));
        assertInvalid(new CreatePerformanceCommand(showId, now, now.plusMinutes(1), null, 1, 1, 1));
        assertThat(performanceRepository.findByShowIdOrderByStartsAtAsc(showId)).isEmpty();
    }

    @Test
    @DisplayName("구역·열·열당 좌석 수가 범위를 벗어나면 INVALID_REQUEST")
    void sizeOutOfRange() {
        long showId = fixtures.newShow();
        LocalDateTime now = fixtures.dbNow();
        LocalDateTime startsAt = now.plusDays(1);

        assertInvalid(new CreatePerformanceCommand(showId, startsAt, now, null, 0, 10, 20));
        assertInvalid(new CreatePerformanceCommand(showId, startsAt, now, null, 27, 10, 20));
        assertInvalid(new CreatePerformanceCommand(showId, startsAt, now, null, 4, 0, 20));
        assertInvalid(new CreatePerformanceCommand(showId, startsAt, now, null, 4, 31, 20));
        assertInvalid(new CreatePerformanceCommand(showId, startsAt, now, null, 4, 10, 0));
        assertInvalid(new CreatePerformanceCommand(showId, startsAt, now, null, 4, 10, 51));
        assertInvalid(new CreatePerformanceCommand(showId, startsAt, now, null, null, 10, 20));
        assertInvalid(new CreatePerformanceCommand(showId, startsAt, now, now, 4, 10, 20));
        assertThat(performanceRepository.findByShowIdOrderByStartsAtAsc(showId)).isEmpty();
    }

    @Test
    @DisplayName("없는 공연에 회차를 만들면 SHOW_NOT_FOUND")
    void unknownShow() {
        LocalDateTime now = fixtures.dbNow();

        assertThatThrownBy(() -> showAdminService.createPerformance(
                new CreatePerformanceCommand(Long.MAX_VALUE, now.plusDays(1), now, null, 1, 1, 1)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.SHOW_NOT_FOUND);
    }

    private Performance create(LocalDateTime openAt, LocalDateTime startsAt, LocalDateTime gateCloseAt) {
        CreatePerformanceResult created = showAdminService.createPerformance(
                new CreatePerformanceCommand(fixtures.newShow(), startsAt, openAt, gateCloseAt, 1, 1, 1));
        return performanceRepository.findById(created.performanceId()).orElseThrow();
    }

    private void assertInvalid(CreatePerformanceCommand command) {
        assertThatThrownBy(() -> showAdminService.createPerformance(command))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }
}
