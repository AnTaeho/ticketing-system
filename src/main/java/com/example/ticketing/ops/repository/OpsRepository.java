package com.example.ticketing.ops.repository;

import com.example.ticketing.ops.domain.FailedTask;
import com.example.ticketing.ops.domain.OutboxStatusCount;
import com.example.ticketing.ops.domain.RefundingHold;
import com.example.ticketing.ops.domain.StalePayment;
import com.example.ticketing.ops.domain.StatusCount;
import com.example.ticketing.ops.dto.OpsPerformance;
import com.example.ticketing.payment.domain.PaymentStatus;
import com.example.ticketing.show.domain.Performance;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 시연용 집계라 여러 기능의 테이블을 읽기만 한다. 상태마다 GROUP BY 한 번씩 읽는다
public interface OpsRepository extends Repository<Performance, Long> {

    // TICKET_ISSUED의 대상은 예매, REFUND_REQUESTED의 대상은 결제다
    String OUTBOX_OF_PERFORMANCE = """
            ((o.taskType = TICKET_ISSUED
              and o.aggregateId in (select b.id from Booking b where b.performanceId = :performanceId))
             or (o.taskType = REFUND_REQUESTED
              and o.aggregateId in (select bp.id from Payment bp join SeatHold h on h.id = bp.holdId
                                     where h.performanceId = :performanceId)))""";

    @Query("""
            select new com.example.ticketing.ops.dto.OpsPerformance(
                   p.id, s.title, s.venueName, p.startsAt, p.openAt, p.gateCloseAt)
              from Performance p
              join Show s on s.id = p.showId
             where p.id = :performanceId""")
    Optional<OpsPerformance> findPerformance(@Param("performanceId") long performanceId);

    @Query("""
            select new com.example.ticketing.ops.domain.StatusCount(s.status, count(s))
              from Seat s where s.performanceId = :performanceId group by s.status""")
    List<StatusCount> countSeats(@Param("performanceId") long performanceId);

    @Query("""
            select new com.example.ticketing.ops.domain.StatusCount(h.status, count(h))
              from SeatHold h where h.performanceId = :performanceId group by h.status""")
    List<StatusCount> countHolds(@Param("performanceId") long performanceId);

    @Query("""
            select new com.example.ticketing.ops.domain.StatusCount(bp.status, count(bp))
              from Payment bp join SeatHold h on h.id = bp.holdId
             where h.performanceId = :performanceId
             group by bp.status""")
    List<StatusCount> countPayments(@Param("performanceId") long performanceId);

    @Query("""
            select new com.example.ticketing.ops.domain.StatusCount(b.status, count(b))
              from Booking b where b.performanceId = :performanceId group by b.status""")
    List<StatusCount> countBookings(@Param("performanceId") long performanceId);

    @Query("select new com.example.ticketing.ops.domain.OutboxStatusCount(o.taskType, o.status, count(o)) "
            + "from OutboxTask o where " + OUTBOX_OF_PERFORMANCE + " group by o.taskType, o.status")
    List<OutboxStatusCount> countOutboxTasks(@Param("performanceId") long performanceId);

    // 최신순
    @Query("select new com.example.ticketing.ops.domain.FailedTask("
            + "o.id, o.taskType, o.aggregateId, o.attempt, o.lastError, o.updatedAt) "
            + "from OutboxTask o where o.status = FAILED and " + OUTBOX_OF_PERFORMANCE
            + " order by o.id desc limit :limit")
    List<FailedTask> findFailedTasks(@Param("performanceId") long performanceId, @Param("limit") int limit);

    @Query("""
            select new com.example.ticketing.ops.domain.RefundingHold(
                   h.id, h.userId, h.seatCount, h.totalAmount, h.updatedAt)
              from SeatHold h
             where h.performanceId = :performanceId and h.status = REFUNDING
             order by h.id desc
             limit :limit""")
    List<RefundingHold> findRefundingHolds(@Param("performanceId") long performanceId, @Param("limit") int limit);

    // 결제가 아직 열린 상태로 staleBefore보다 먼저 만들어진 것
    @Query("""
            select new com.example.ticketing.ops.domain.StalePayment(
                   bp.id, bp.holdId, bp.status, bp.createdAt, bp.lastError)
              from Payment bp join SeatHold h on h.id = bp.holdId
             where h.performanceId = :performanceId
               and bp.status in :openStatuses
               and bp.createdAt < :staleBefore
             order by bp.id desc
             limit :limit""")
    List<StalePayment> findStalePayments(@Param("performanceId") long performanceId,
                                         @Param("openStatuses") Collection<PaymentStatus> openStatuses,
                                         @Param("staleBefore") LocalDateTime staleBefore, @Param("limit") int limit);
}
