package com.example.ticketing.show.service;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.show.domain.Performance;
import com.example.ticketing.show.domain.Show;
import com.example.ticketing.show.dto.PerformanceSummary;
import com.example.ticketing.show.repository.PerformanceRepository;
import com.example.ticketing.show.repository.ShowRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 다른 기능이 회차를 읽는 창구. 여러 회차는 회차·공연을 한 번씩만 읽는다
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PerformanceReader {

    private final PerformanceRepository performanceRepository;
    private final ShowRepository showRepository;

    public PerformanceSummary get(long performanceId) {
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new ApiException(ErrorCode.PERFORMANCE_NOT_FOUND,
                        Map.of("performanceId", performanceId)));
        Show show = showRepository.findById(performance.getShowId())
                .orElseThrow(() -> new IllegalStateException("회차 " + performanceId + "의 공연이 없다"));
        return PerformanceSummary.from(performance, show);
    }

    // 없는 회차 ID는 결과에서 빠진다
    public Map<Long, PerformanceSummary> findAll(Collection<Long> performanceIds) {
        List<Performance> performances = performanceRepository.findAllById(performanceIds);
        Map<Long, Show> shows = showRepository.findAllById(
                        performances.stream().map(Performance::getShowId).distinct().toList()).stream()
                .collect(Collectors.toMap(Show::getId, Function.identity()));
        return performances.stream().collect(Collectors.toMap(Performance::getId,
                performance -> PerformanceSummary.from(performance, shows.get(performance.getShowId()))));
    }
}
