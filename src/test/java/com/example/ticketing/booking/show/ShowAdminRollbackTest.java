package com.example.ticketing.booking.show;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.example.ticketing.booking.show.dto.NewPerformance;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@BookingIntegrationTest
class ShowAdminRollbackTest {

    @Autowired
    private ShowAdminService showAdminService;
    @Autowired
    private PerformanceRepository performanceRepository;
    @Autowired
    private BookingFixtures fixtures;
    @MockitoSpyBean
    private SeatBulkInserter seatBulkInserter;

    @Test
    @DisplayName("좌석 INSERT가 실패하면 회차 INSERT도 롤백된다")
    void seatInsertFailureRollsBackPerformance() {
        long showId = fixtures.newShow();
        LocalDateTime now = fixtures.dbNow();
        // 프록시로 스텁하면 MANDATORY 트랜잭션 검사에 먼저 걸리므로 감싼 스파이에 직접 건다
        SeatBulkInserter spy = AopTestUtils.getUltimateTargetObject(seatBulkInserter);
        doThrow(new DataIntegrityViolationException("seat insert failed"))
                .when(spy).insert(anyLong(), anyList());

        assertThatThrownBy(() -> showAdminService.createPerformance(
                new NewPerformance(showId, now.plusDays(1), now, null, 1, 1, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(performanceRepository.findByShowIdOrderByStartsAtAsc(showId)).isEmpty();
    }
}
