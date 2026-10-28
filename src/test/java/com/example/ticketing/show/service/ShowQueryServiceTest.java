package com.example.ticketing.show.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.show.domain.PerformanceState;
import com.example.ticketing.show.dto.PerformanceResult;
import com.example.ticketing.show.dto.ShowDetailResult;
import com.example.ticketing.show.dto.ShowSummaryResult;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ShowQueryServiceTest {

    @Autowired
    private ShowQueryService showQueryService;
    @Autowired
    private Fixtures fixtures;

    @Test
    @DisplayName("공연 상세의 회차 state는 DB 시각 기준으로 오픈 전·예매 중·마감을 가른다")
    void performanceStates() {
        long showId = fixtures.newShow();
        LocalDateTime now = fixtures.dbNow();
        long closed = fixtures.newPerformance(showId, now.minusDays(2), now.minusMinutes(1), 1, 1, 1);
        long open = fixtures.newPerformance(showId, now.minusMinutes(1), now.plusDays(1), 1, 1, 1);
        long beforeOpen = fixtures.newPerformance(showId, now.plusHours(1), now.plusDays(2), 1, 1, 1);

        ShowDetailResult detail = showQueryService.getShow(showId);

        assertThat(detail.id()).isEqualTo(showId);
        assertThat(detail.performances())
                .extracting(PerformanceResult::id, PerformanceResult::state)
                .containsExactly(
                        tuple(closed, PerformanceState.CLOSED),
                        tuple(open, PerformanceState.OPEN),
                        tuple(beforeOpen, PerformanceState.BEFORE_OPEN));
    }

    @Test
    @DisplayName("공연 목록의 nextPerformance는 아직 시작하지 않은 가장 이른 회차이고 없으면 null이다")
    void nextPerformance() {
        long showId = fixtures.newShow();
        LocalDateTime now = fixtures.dbNow();
        fixtures.newPerformance(showId, now.minusDays(2), now.minusMinutes(1), 1, 1, 1);
        fixtures.newPerformance(showId, now.plusHours(1), now.plusDays(2), 1, 1, 1);
        long open = fixtures.newPerformance(showId, now.minusMinutes(1), now.plusDays(1), 1, 1, 1);
        long finishedShowId = fixtures.newShow();
        fixtures.newPerformance(finishedShowId, now.minusDays(2), now.minusDays(1), 1, 1, 1);

        var shows = showQueryService.listShows();

        ShowSummaryResult summary = shows.stream().filter(s -> s.id() == showId).findFirst().orElseThrow();
        assertThat(summary.nextPerformance().id()).isEqualTo(open);
        assertThat(summary.nextPerformance().state()).isEqualTo(PerformanceState.OPEN);
        ShowSummaryResult finished = shows.stream().filter(s -> s.id() == finishedShowId).findFirst().orElseThrow();
        assertThat(finished.nextPerformance()).isNull();
    }

    @Test
    @DisplayName("없는 공연을 조회하면 SHOW_NOT_FOUND로 거절한다")
    void unknownShow() {
        assertThatThrownBy(() -> showQueryService.getShow(Long.MAX_VALUE))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.SHOW_NOT_FOUND);
    }
}
