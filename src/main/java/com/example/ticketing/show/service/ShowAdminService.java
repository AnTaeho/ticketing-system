package com.example.ticketing.show.service;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.seat.domain.SeatLayout;
import com.example.ticketing.seat.repository.SeatBulkInserter;
import com.example.ticketing.show.domain.Performance;
import com.example.ticketing.show.domain.Show;
import com.example.ticketing.show.dto.CreatePerformanceCommand;
import com.example.ticketing.show.dto.CreatePerformanceResult;
import com.example.ticketing.show.dto.CreateShowCommand;
import com.example.ticketing.show.dto.CreateShowResult;
import com.example.ticketing.show.repository.PerformanceRepository;
import com.example.ticketing.show.repository.ShowRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShowAdminService {

    static final Duration DEFAULT_GATE_OPEN_PERIOD = Duration.ofHours(24);
    static final int MAX_ROWS = 30;
    static final int MAX_SEATS_PER_ROW = 50;

    private final ShowRepository showRepository;
    private final PerformanceRepository performanceRepository;
    private final SeatBulkInserter seatBulkInserter;

    @Transactional
    public CreateShowResult createShow(CreateShowCommand command) {
        Show show = showRepository.save(
                Show.create(command.title().trim(), command.venueName().trim(), command.description()));
        return new CreateShowResult(show.getId());
    }

    // 회차와 좌석을 한 트랜잭션에서 만든다. 좌석 INSERT가 실패하면 회차도 남지 않는다
    @Transactional
    public CreatePerformanceResult createPerformance(CreatePerformanceCommand command) {
        validate(command);
        if (!showRepository.existsById(command.showId())) {
            throw new ApiException(ErrorCode.SHOW_NOT_FOUND, Map.of("showId", command.showId()));
        }
        LocalDateTime gateCloseAt = resolveGateCloseAt(command.openAt(), command.startsAt(), command.gateCloseAt());
        Performance performance = performanceRepository.save(
                Performance.create(command.showId(), command.startsAt(), command.openAt(), gateCloseAt));
        int seatCount = seatBulkInserter.insert(performance.getId(),
                SeatLayout.generate(command.sections(), command.rows(), command.seatsPerRow()));
        return new CreatePerformanceResult(performance.getId(), seatCount);
    }

    static LocalDateTime resolveGateCloseAt(LocalDateTime openAt, LocalDateTime startsAt, LocalDateTime requested) {
        LocalDateTime gateCloseAt = requested != null ? requested : openAt.plus(DEFAULT_GATE_OPEN_PERIOD);
        return gateCloseAt.isAfter(startsAt) ? startsAt : gateCloseAt;
    }

    private static void validate(CreatePerformanceCommand command) {
        if (!command.openAt().isBefore(command.startsAt())) {
            throw invalid(Map.of("reason", "OPEN_NOT_BEFORE_START"));
        }
        if (command.gateCloseAt() != null && !command.gateCloseAt().isAfter(command.openAt())) {
            throw invalid(Map.of("reason", "GATE_CLOSE_NOT_AFTER_OPEN"));
        }
        checkRange("sections", command.sections(), SeatLayout.MAX_SECTIONS);
        checkRange("rows", command.rows(), MAX_ROWS);
        checkRange("seatsPerRow", command.seatsPerRow(), MAX_SEATS_PER_ROW);
    }

    private static void checkRange(String field, int value, int max) {
        if (value < 1 || value > max) {
            throw invalid(Map.of("reason", "OUT_OF_RANGE", "field", field, "max", max));
        }
    }

    private static ApiException invalid(Map<String, Object> detail) {
        return new ApiException(ErrorCode.INVALID_REQUEST, detail);
    }
}
