package com.example.ticketing.booking.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.booking.outbox.domain.OutboxTaskType;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@BookingIntegrationTest
class OutboxWriterTest {

    @Autowired
    private OutboxWriter outboxWriter;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("트랜잭션 밖에서 부르면 IllegalTransactionStateException이고 행이 생기지 않는다")
    void outsideTransactionFails() {
        long aggregateId = randomAggregateId();

        assertThatThrownBy(() -> outboxWriter.write(OutboxTaskType.TICKET_ISSUED, aggregateId, "{}"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count(aggregateId)).isZero();
    }

    @Test
    @DisplayName("트랜잭션 안에서는 PENDING·attempt 0·next_attempt_at = NOW로 기록하고, 롤백되면 같이 사라진다")
    void writesInsideTransaction() {
        long aggregateId = randomAggregateId();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Long id = tx.execute(s -> outboxWriter.write(OutboxTaskType.REFUND_REQUESTED, aggregateId,
                "{\"paymentId\":" + aggregateId + "}"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT task_type, aggregate_id, payload, status, attempt, "
                + "locked_until, last_error, TIMESTAMPDIFF(MICROSECOND, next_attempt_at, NOW(6)) AS age_us "
                + "FROM outbox_task WHERE id = ?", id);
        assertThat(row).containsEntry("task_type", "REFUND_REQUESTED").containsEntry("aggregate_id", aggregateId)
                .containsEntry("payload", "{\"paymentId\":" + aggregateId + "}").containsEntry("status", "PENDING")
                .containsEntry("attempt", 0).containsEntry("locked_until", null).containsEntry("last_error", null);
        assertThat(((Number) row.get("age_us")).longValue()).isBetween(0L, 5_000_000L);

        long rolledBack = randomAggregateId();
        tx.executeWithoutResult(s -> {
            outboxWriter.write(OutboxTaskType.TICKET_ISSUED, rolledBack, "{}");
            s.setRollbackOnly();
        });
        assertThat(count(rolledBack)).isZero();
        jdbcTemplate.update("DELETE FROM outbox_task WHERE id = ?", id);
    }

    private long count(long aggregateId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_task WHERE aggregate_id = ?",
                Long.class, aggregateId);
    }

    // 실제 예매·결제 ID와 겹치지 않게 음수를 쓴다
    private static long randomAggregateId() {
        return -ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2);
    }
}
