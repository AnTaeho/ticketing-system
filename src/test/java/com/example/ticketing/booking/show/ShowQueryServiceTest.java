package com.example.ticketing.booking.show;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.show.domain.PerformanceState;
import com.example.ticketing.booking.show.dto.PerformanceView;
import com.example.ticketing.booking.show.dto.ShowDetail;
import com.example.ticketing.booking.show.dto.ShowSummary;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@BookingIntegrationTest
class ShowQueryServiceTest {

    @Autowired
    private ShowQueryService showQueryService;
    @Autowired
    private BookingFixtures fixtures;

    @Test
    @DisplayName("공연 상세의 회차 state는 DB 시각 기준으로 오픈 전·예매 중·마감을 가른다")
    void performanceStates() {
        long showId = fixtures.newShow();
        LocalDateTime now = fixtures.dbNow();
        long closed = fixtures.newPerformance(showId, now.minusDays(2), now.minusMinutes(1), 1, 1, 1);
        long open = fixtures.newPerformance(showId, now.minusMinutes(1), now.plusDays(1), 1, 1, 1);
        long beforeOpen = fixtures.newPerformance(showId, now.plusHours(1), now.plusDays(2), 1, 1, 1);

        ShowDetail detail = showQueryService.getShow(showId);

        assertThat(detail.id()).isEqualTo(showId);
        assertThat(detail.performances())
                .extracting(PerformanceView::id, PerformanceView::state)
                .containsExactly(
                        tuple(closed, PerformanceState.CLOSED),
                        tuple(open, PerformanceState.OPEN),
                        tuple(beforeOpen, PerformanceState.BEFORE_OPEN));
    }

    @Test
    @DisplayName("공연 목록의 nextPerformance는 아직 시작하지 않은 가장 이른 회차, 없으면 null")
    void nextPerformance() {
        long showId = fixtures.newShow();
        LocalDateTime now = fixtures.dbNow();
        fixtures.newPerformance(showId, now.minusDays(2), now.minusMinutes(1), 1, 1, 1);
        fixtures.newPerformance(showId, now.plusHours(1), now.plusDays(2), 1, 1, 1);
        long open = fixtures.newPerformance(showId, now.minusMinutes(1), now.plusDays(1), 1, 1, 1);
        long finishedShowId = fixtures.newShow();
        fixtures.newPerformance(finishedShowId, now.minusDays(2), now.minusDays(1), 1, 1, 1);

        var shows = showQueryService.listShows();

        ShowSummary summary = shows.stream().filter(s -> s.id() == showId).findFirst().orElseThrow();
        assertThat(summary.nextPerformance().id()).isEqualTo(open);
        assertThat(summary.nextPerformance().state()).isEqualTo(PerformanceState.OPEN);
        ShowSummary finished = shows.stream().filter(s -> s.id() == finishedShowId).findFirst().orElseThrow();
        assertThat(finished.nextPerformance()).isNull();
    }

    @Test
    @DisplayName("없는 공연은 SHOW_NOT_FOUND")
    void unknownShow() {
        assertThatThrownBy(() -> showQueryService.getShow(Long.MAX_VALUE))
                .isInstanceOf(BookingException.class)
                .extracting(e -> ((BookingException) e).getCode())
                .isEqualTo(BookingErrorCode.SHOW_NOT_FOUND);
    }
}
