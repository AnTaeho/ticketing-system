package com.example.ticketing.show.service;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.show.domain.Performance;
import com.example.ticketing.show.domain.Show;
import com.example.ticketing.show.dto.ShowDetailResult;
import com.example.ticketing.show.dto.ShowSummaryResult;
import com.example.ticketing.show.repository.PerformanceRepository;
import com.example.ticketing.show.repository.ShowRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShowQueryService {

    private final ShowRepository showRepository;
    private final PerformanceRepository performanceRepository;
    private final DbClock dbClock;

    public List<ShowSummaryResult> listShows() {
        LocalDateTime now = dbClock.now();
        BinaryOperator<Performance> earlierId = (a, b) -> a.getId() <= b.getId() ? a : b;
        Map<Long, Performance> nextByShow = performanceRepository.findNextPerformances(now).stream()
                .collect(Collectors.toMap(Performance::getShowId, p -> p, earlierId));
        return showRepository.findAll(Sort.by("id")).stream()
                .map(show -> ShowSummaryResult.from(show, nextByShow.get(show.getId()), now))
                .toList();
    }

    public ShowDetailResult getShow(Long showId) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ApiException(ErrorCode.SHOW_NOT_FOUND, Map.of("showId", showId)));
        LocalDateTime now = dbClock.now();
        List<Performance> performances = performanceRepository.findByShowIdOrderByStartsAtAsc(showId).stream()
                .sorted(Comparator.comparing(Performance::getStartsAt).thenComparing(Performance::getId))
                .toList();
        return ShowDetailResult.from(show, performances, now);
    }
}
