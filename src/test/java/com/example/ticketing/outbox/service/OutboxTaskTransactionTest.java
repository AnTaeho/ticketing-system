package com.example.ticketing.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.support.TestProperties;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.outbox.domain.DueOutboxTask;
import com.example.ticketing.outbox.domain.FailedAttempt;
import com.example.ticketing.outbox.domain.OutboxTaskStatus;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.dto.ClaimedOutboxTask;
import com.example.ticketing.outbox.repository.OutboxTaskJdbcRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 테스트 DB는 Hibernate가 만든 CHECK 제약 때문에 enum 밖 종류를 넣을 수 없어 저장소를 목으로 둔다
class OutboxTaskTransactionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 12, 0);

    private final OutboxTaskJdbcRepository outboxTaskJdbcRepository = mock(OutboxTaskJdbcRepository.class);
    private final DbClock dbClock = mock(DbClock.class);

    @Test
    @DisplayName("모르는 작업 종류는 실패로 기록하고 같은 배치의 아는 작업만 집는다")
    void unknownTaskTypeIsFailedAttempt() {
        when(dbClock.now()).thenReturn(NOW);
        when(outboxTaskJdbcRepository.claimDue(10)).thenReturn(List.of(
                new DueOutboxTask(1, "LEGACY_TASK", 100, "{}", 0),
                new DueOutboxTask(2, "TICKET_ISSUED", 200, "{\"bookingId\":200}", 0),
                new DueOutboxTask(3, "LEGACY_TASK", 300, "{}", 4)));

        List<ClaimedOutboxTask> claimed = transaction().claim(10);

        assertThat(claimed).containsExactly(new ClaimedOutboxTask(2, OutboxTaskType.TICKET_ISSUED, 200,
                "{\"bookingId\":200}", 0, NOW.plusSeconds(30)));
        verify(outboxTaskJdbcRepository).lock(List.of(2L), NOW.plusSeconds(30));
        verify(outboxTaskJdbcRepository).markFailedAttemptLocked(1,
                new FailedAttempt(OutboxTaskStatus.PENDING, 1, 2, "핸들러 없음: LEGACY_TASK"));
        verify(outboxTaskJdbcRepository).markFailedAttemptLocked(3,
                new FailedAttempt(OutboxTaskStatus.FAILED, 5, 32, "핸들러 없음: LEGACY_TASK"));
    }

    @Test
    @DisplayName("집은 행이 모두 모르는 종류면 아무것도 잠그지 않고 빈 목록을 돌려준다")
    void onlyUnknownTypesLockNothing() {
        when(outboxTaskJdbcRepository.claimDue(10))
                .thenReturn(List.of(new DueOutboxTask(1, "LEGACY_TASK", 100, "{}", 0)));

        assertThat(transaction().claim(10)).isEmpty();

        verify(outboxTaskJdbcRepository, never()).lock(any(), any());
        verify(outboxTaskJdbcRepository).markFailedAttemptLocked(eq(1L), any());
        verify(outboxTaskJdbcRepository, never()).markFailedAttempt(anyLong(), any(), any());
    }

    private OutboxTaskTransaction transaction() {
        TicketingProperties properties = TestProperties.empty()
                .outbox(new TicketingProperties.Outbox(true, 500, 10, Duration.ofSeconds(30), 5, Duration.ofSeconds(60)))
                .build();
        return new OutboxTaskTransaction(outboxTaskJdbcRepository, dbClock, properties);
    }
}
