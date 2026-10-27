package com.example.ticketing.booking.show.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.common.DbClock;
import com.example.ticketing.booking.show.ShowAdminService;
import com.example.ticketing.booking.show.ShowRepository;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

// 테스트 컨텍스트는 seed.enabled=false라 러너 빈이 없다. 시드 로직은 직접 만든 러너로 확인한다
@BookingIntegrationTest
class LocalSeedRunnerTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private ShowAdminService showAdminService;
    @Autowired
    private ShowRepository showRepository;
    @Autowired
    private DbClock dbClock;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("booking.seed.enabled=false면 시드 러너 빈이 없다")
    void noBeanWhenDisabled() {
        assertThat(context.getBeanNamesForType(LocalSeedRunner.class)).isEmpty();
    }

    @Test
    @DisplayName("시드를 두 번 돌려도 공연 1개, 회차 2개(예매 중·10분 뒤 오픈), 각 800석만 생긴다")
    void seedIsIdempotent() {
        deleteExistingSeed();
        LocalSeedRunner runner = new LocalSeedRunner(showAdminService, showRepository, dbClock);

        runner.seed();
        runner.seed();

        List<Long> showIds = jdbcTemplate.queryForList("SELECT id FROM show_info WHERE title = ?",
                Long.class, LocalSeedRunner.SEED_TITLE);
        assertThat(showIds).hasSize(1);
        List<Map<String, Object>> performances = jdbcTemplate.queryForList("""
                SELECT p.id, p.open_at, p.starts_at,
                       (SELECT COUNT(*) FROM seat s WHERE s.performance_id = p.id) AS seat_count
                FROM performance p WHERE p.show_id = ? ORDER BY p.open_at""", showIds.get(0));
        assertThat(performances).hasSize(2);

        LocalDateTime now = dbClock.now();
        LocalDateTime openNow = (LocalDateTime) performances.get(0).get("open_at");
        LocalDateTime openSoon = (LocalDateTime) performances.get(1).get("open_at");
        assertThat(openNow).isBefore(now);
        assertThat(openSoon).isAfter(now.plusMinutes(9)).isBefore(now.plusMinutes(11));
        for (Map<String, Object> performance : performances) {
            assertThat(((Number) performance.get("seat_count")).longValue()).isEqualTo(800L);
            assertThat((LocalDateTime) performance.get("starts_at")).isAfter(now.plusDays(1));
        }
    }

    // 이 테스트 DB에 이전 실행이 남긴 시드만 지워 첫 호출이 실제로 만들게 한다
    private void deleteExistingSeed() {
        List<Long> showIds = jdbcTemplate.queryForList("SELECT id FROM show_info WHERE title = ?",
                Long.class, LocalSeedRunner.SEED_TITLE);
        for (Long showId : showIds) {
            jdbcTemplate.update("DELETE s FROM seat s JOIN performance p ON s.performance_id = p.id WHERE p.show_id = ?",
                    showId);
            jdbcTemplate.update("DELETE FROM performance WHERE show_id = ?", showId);
            jdbcTemplate.update("DELETE FROM show_info WHERE id = ?", showId);
        }
    }
}
