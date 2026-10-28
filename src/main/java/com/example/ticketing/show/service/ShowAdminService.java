package com.example.ticketing.show.service;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.seat.domain.SeatLayout;
import com.example.ticketing.seat.repository.SeatBulkInserter;
import com.example.ticketing.show.domain.Performance;
import com.example.ticketing.show.domain.Show;
import com.example.ticketing.show.dto.NewPerformance;
import com.example.ticketing.show.dto.PerformanceCreated;
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
    static final int MAX_SECTIONS = 26;
    static final int MAX_ROWS = 30;
    static final int MAX_SEATS_PER_ROW = 50;

    private final ShowRepository showRepository;
    private final PerformanceRepository performanceRepository;
    private final SeatBulkInserter seatBulkInserter;

    @Transactional
    public Long createShow(String title, String venueName, String description) {
        if (isBlank(title) || isBlank(venueName)) {
            throw invalid("title, venueName은 비울 수 없다");
        }
        return showRepository.save(Show.create(title.trim(), venueName.trim(), description)).getId();
    }

    // 회차와 좌석을 한 트랜잭션에서 만든다. 좌석 INSERT가 실패하면 회차도 남지 않는다
    @Transactional
    public PerformanceCreated createPerformance(NewPerformance command) {
        validate(command);
        if (!showRepository.existsById(command.showId())) {
            throw new ApiException(ErrorCode.SHOW_NOT_FOUND, Map.of("showId", command.showId()));
        }
        LocalDateTime gateCloseAt = resolveGateCloseAt(command.openAt(), command.startsAt(), command.gateCloseAt());
        Performance performance = performanceRepository.save(
                Performance.create(command.showId(), command.startsAt(), command.openAt(), gateCloseAt));
        int seatCount = seatBulkInserter.insert(performance.getId(),
                SeatLayout.generate(command.sections(), command.rows(), command.seatsPerRow()));
        return new PerformanceCreated(performance.getId(), seatCount);
    }

    static LocalDateTime resolveGateCloseAt(LocalDateTime openAt, LocalDateTime startsAt, LocalDateTime requested) {
        LocalDateTime gateCloseAt = requested != null ? requested : openAt.plus(DEFAULT_GATE_OPEN_PERIOD);
        return gateCloseAt.isAfter(startsAt) ? startsAt : gateCloseAt;
    }

    private static void validate(NewPerformance command) {
        if (command.showId() == null || command.startsAt() == null || command.openAt() == null
                || command.sections() == null || command.rows() == null || command.seatsPerRow() == null) {
            throw invalid("showId, startsAt, openAt, sections, rows, seatsPerRow는 필수다");
        }
        if (!command.openAt().isBefore(command.startsAt())) {
            throw invalid("openAt은 startsAt보다 앞서야 한다");
        }
        if (command.gateCloseAt() != null && !command.gateCloseAt().isAfter(command.openAt())) {
            throw invalid("gateCloseAt은 openAt보다 뒤여야 한다");
        }
        checkRange("sections", command.sections(), MAX_SECTIONS);
        checkRange("rows", command.rows(), MAX_ROWS);
        checkRange("seatsPerRow", command.seatsPerRow(), MAX_SEATS_PER_ROW);
    }

    private static void checkRange(String field, int value, int max) {
        if (value < 1 || value > max) {
            throw invalid("%s는 1~%d 사이여야 한다".formatted(field, max));
        }
    }

    private static ApiException invalid(String reason) {
        return new ApiException(ErrorCode.INVALID_REQUEST, Map.of("reason", reason));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
