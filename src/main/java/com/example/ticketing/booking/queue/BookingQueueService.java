package com.example.ticketing.booking.queue;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.common.DbClock;
import com.example.ticketing.booking.common.ThrottledWarn;
import com.example.ticketing.booking.queue.BookingQueueRepository.Entered;
import com.example.ticketing.booking.queue.dto.QueueEntered;
import com.example.ticketing.booking.queue.dto.QueuePosition;
import com.example.ticketing.booking.seat.SeatCatalog;
import com.example.ticketing.booking.seat.SeatCatalog.PerformanceInfo;
import com.example.ticketing.booking.show.domain.PerformanceState;
import io.lettuce.core.RedisException;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

// 진입·조회는 Redis가 없으면 줄 순서를 지킬 수 없으므로 503으로 막는다(fail-closed)
@Slf4j
@Service
public class BookingQueueService {

    private static final double MIN_RATE = 0.1;

    private final BookingQueueRepository repository;
    private final SeatCatalog seatCatalog;
    private final DbClock dbClock;
    private final int capacity;
    private final long processingTtlMs;
    private final long waitingTtlMs;
    private final ThrottledWarn unavailableWarn = new ThrottledWarn(log);

    public BookingQueueService(BookingQueueRepository repository, SeatCatalog seatCatalog, DbClock dbClock,
                               BookingProperties properties) {
        this.repository = repository;
        this.seatCatalog = seatCatalog;
        this.dbClock = dbClock;
        this.capacity = properties.queue().capacity();
        this.processingTtlMs = properties.queue().processingTokenTtl().toMillis();
        this.waitingTtlMs = properties.queue().waitingTokenTtl().toMillis();
    }

    public QueueEntered enter(long performanceId, long userId) {
        ensureOpen(performanceId);
        String member = userId + "." + UUID.randomUUID();
        long now = System.currentTimeMillis();
        try {
            Entered entered = repository.enter(performanceId, userId, member, now, now + processingTtlMs,
                    capacity, waitingTtlMs);
            return new QueueEntered(entered.member(), entered.status());
        } catch (DataAccessException | RedisException e) {
            throw unavailable("enter", performanceId, e);
        }
    }

    // 남의 토큰은 있는지 없는지 드러내지 않도록 없는 토큰과 같은 404로 답한다
    public QueuePosition status(long performanceId, long userId, String token) {
        if (!ownedBy(token, userId)) {
            throw new BookingException(BookingErrorCode.QUEUE_TOKEN_NOT_FOUND);
        }
        try {
            return lookUp(performanceId, token, System.currentTimeMillis());
        } catch (DataAccessException | RedisException e) {
            throw unavailable("status", performanceId, e);
        }
    }

    // 점수를 현재 시각과 비교하므로 정리 전의 만료 토큰도 통과하지 못한다. Redis 예외는 호출자가 처리한다
    public boolean isAdmitted(long performanceId, String token, long userId) {
        if (!ownedBy(token, userId)) {
            return false;
        }
        Double expireAt = repository.processingScore(performanceId, token);
        return expireAt != null && expireAt > System.currentTimeMillis();
    }

    // 선점이 커밋된 뒤에만 부른다. 반환: 처리열에서 지웠는지
    public boolean consume(long performanceId, String token, long userId) {
        if (!ownedBy(token, userId)) {
            return false;
        }
        return repository.consume(performanceId, userId, token) > 0;
    }

    // 승격은 waiting → processing 한 방향이라 waiting을 먼저 봐야 그 사이 승격돼도 놓치지 않는다
    private QueuePosition lookUp(long performanceId, String token, long now) {
        Double enteredAt = repository.waitingScore(performanceId, token);
        if (enteredAt != null && enteredAt + waitingTtlMs > now) {
            Long rank = repository.waitingRank(performanceId, token);
            if (rank != null) {
                long position = rank + 1;
                double rate = Math.max(repository.rate(performanceId), MIN_RATE);
                return QueuePosition.waiting(position, (long) Math.ceil(position / rate));
            }
        }
        Double expireAt = repository.processingScore(performanceId, token);
        if (expireAt != null && expireAt > now) {
            return QueuePosition.processing();
        }
        return QueuePosition.expired();
    }

    // 상세 화면이 DB 시각으로 「예매하기」를 띄우므로 같은 기준으로 본다. 진입마다 DB를 치지 않게 차이만 캐시한다
    private void ensureOpen(long performanceId) {
        PerformanceInfo performance = seatCatalog.performance(performanceId);
        if (PerformanceState.of(performance.openAt(), performance.startsAt(), dbClock.estimatedNow())
                != PerformanceState.OPEN) {
            throw new BookingException(BookingErrorCode.NOT_OPEN, Map.of("performanceId", performanceId));
        }
    }

    private static boolean ownedBy(String token, long userId) {
        if (token == null) {
            return false;
        }
        int dot = token.indexOf('.');
        return dot > 0 && token.substring(0, dot).equals(String.valueOf(userId));
    }

    private BookingException unavailable(String op, long performanceId, RuntimeException e) {
        unavailableWarn.warn("[booking queue] Redis 실패로 대기열을 막는다 op={}, performanceId={}: {}", op, performanceId, e.toString());
        return new BookingException(BookingErrorCode.QUEUE_UNAVAILABLE);
    }
}
