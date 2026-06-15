package com.example.ticketing;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.global.chaos.ChaosState;
import com.example.ticketing.global.exception.LockAcquisitionFailedException;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.global.resilience.CircuitBreakerStatsHolder;
import com.example.ticketing.reservation.repository.ReservationRepository;
import com.example.ticketing.reservation.service.v5cb.TicketServiceV5CB;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V5CB — Circuit Breaker + Fallback Chain 통합 테스트 (모킹 없음)
 *
 * 실제 Redis(Redisson), MySQL, Resilience4j CB, ChaosAspect를 사용하여
 * CB 상태 전환 및 폴백 동작을 검증한다.
 *
 * 실행 환경: MySQL, Redis 실행 필요 (Kafka 불필요)
 * ChaosAspect가 V5.reserve()를 인터셉트하여 RedisCommandTimeoutException을 주입한다.
 */
@SpringBootTest
class CircuitBreakerV5CBTest {

    private static final String LOCK_KEY_PREFIX = "lock:concert:";

    @Autowired private TicketServiceV5CB         service;
    @Autowired private ChaosState                chaosState;
    @Autowired private CircuitBreaker            redisLockCircuitBreaker;
    @Autowired private CircuitBreakerStatsHolder statsHolder;
    @Autowired private ConcertRepository         concertRepository;
    @Autowired private ReservationRepository     reservationRepository;
    @Autowired private RedissonClient            redissonClient;

    private Long concertId;

    @BeforeEach
    void setUp() {
        chaosState.setRedisBlocked(false);
        redisLockCircuitBreaker.reset();
        statsHolder.reset();

        reservationRepository.deleteAll();
        concertRepository.deleteAll();
        concertId = concertRepository.save(Concert.create("CB 테스트 공연", 1000)).getId();
    }

    @AfterEach
    void tearDown() {
        chaosState.setRedisBlocked(false);
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + concertId);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    // ── 1. 정상 상태(CLOSED) — Redis 경로 성공 ──────────────────────────────────

    @Test
    @DisplayName("정상 상태(CLOSED) → V5 Redis 경로 성공, redisPathCount 증가, DB 저장 확인")
    void 정상_상태_redis_경로_성공() {
        service.reserve(concertId, 1L);

        assertThat(redisLockCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(statsHolder.getRedisPathCount()).isEqualTo(1);
        assertThat(statsHolder.getFallbackPathCount()).isEqualTo(0);
        assertThat(reservationRepository.countByConcertId(concertId)).isEqualTo(1);
    }

    // ── 2. Redis 장애 반복 → CB OPEN → V2 폴백 ──────────────────────────────────

    @Test
    @DisplayName("Redis 장애 10회 반복(minimumNumberOfCalls=10, threshold=50%) → CB OPEN, V2 폴백 DB 저장 확인")
    void redis_장애_10회_cb_open_v2_폴백() {
        // ChaosAspect가 V5.reserve() 진입 시 RedisCommandTimeoutException을 즉시 던진다
        chaosState.setRedisBlocked(true);

        int requestCount = 10;
        for (int i = 1; i <= requestCount; i++) {
            service.reserve(concertId, (long) i);
        }

        assertThat(redisLockCircuitBreaker.getState())
                .as("10회 Redis 실패 → failureRate=100% > 50% → CB OPEN")
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(statsHolder.getFallbackPathCount())
                .as("모든 요청이 V2 폴백 경로로 처리됨")
                .isEqualTo(requestCount);
        assertThat(statsHolder.getRedisPathCount())
                .as("Redis 경로 성공 0건")
                .isEqualTo(0);
        assertThat(reservationRepository.countByConcertId(concertId))
                .as("V2 폴백으로 DB 예약이 저장됨")
                .isEqualTo(requestCount);

        System.out.printf("%n=== CB OPEN 검증 ===%n");
        System.out.printf("CB 상태: %s%n", redisLockCircuitBreaker.getState());
        System.out.printf("Redis 경로: %d / 폴백 경로: %d%n",
                statsHolder.getRedisPathCount(), statsHolder.getFallbackPathCount());
    }

    // ── 3. LockAcquisitionFailedException → 폴백 없이 전파 ─────────────────────

    @Test
    @Timeout(12)  // waitTime=5초 + 여유
    @DisplayName("락 경합(waitTime 5초 초과) → LockAcquisitionFailedException 전파, V2 폴백 미실행, CB 실패 카운트 미증가")
    void lock_경합_예외는_폴백_없이_전파() throws Exception {
        // 테스트 스레드가 락을 보유 → 별도 스레드의 tryLock(5s) 실패 유발
        RLock heldLock = redissonClient.getLock(LOCK_KEY_PREFIX + concertId);
        heldLock.lock(10, TimeUnit.SECONDS);

        // Redisson RLock은 재진입 락이므로 같은 스레드에서 호출하면 즉시 성공한다.
        // 별도 스레드에서 호출해야 실제 waitTime 초과 → LockAcquisitionFailedException 발생한다.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> service.reserve(concertId, 1L));
        executor.shutdown();

        assertThatThrownBy(() -> future.get(8, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LockAcquisitionFailedException.class);

        assertThat(reservationRepository.countByConcertId(concertId))
                .as("V2 폴백 미실행 → DB 예약 없음")
                .isEqualTo(0);
        assertThat(statsHolder.getFallbackPathCount())
                .as("폴백 카운트 증가 없음")
                .isEqualTo(0);
        assertThat(redisLockCircuitBreaker.getMetrics().getNumberOfFailedCalls())
                .as("LockAcquisitionFailed는 recordException 미해당 → CB 실패 카운트 0")
                .isEqualTo(0);
    }

    // ── 4. SoldOutException → 폴백 없이 전파 ────────────────────────────────────

    @Test
    @DisplayName("재고 소진 → SoldOutException 전파, V2 폴백 미실행, CB 실패 카운트 미증가")
    void sold_out_예외는_폴백_없이_전파() {
        Concert soldOut = concertRepository.save(Concert.create("매진 공연", 0));

        assertThatThrownBy(() -> service.reserve(soldOut.getId(), 1L))
                .isInstanceOf(SoldOutException.class);

        assertThat(statsHolder.getFallbackPathCount())
                .as("SoldOut은 폴백 없이 전파 → fallbackPathCount 0")
                .isEqualTo(0);
        assertThat(redisLockCircuitBreaker.getMetrics().getNumberOfFailedCalls())
                .as("SoldOut은 recordException 미해당 → CB 실패 카운트 0")
                .isEqualTo(0);
    }

    // ── 5. CB OPEN → HALF_OPEN → CLOSED 복구 흐름 ─────────────────────────────

    @Test
    @DisplayName("CLOSED → OPEN(Redis 장애) → HALF_OPEN(수동 전환) → CLOSED(성공 3회) 전체 상태 전환 검증")
    void cb_open_halfopen_closed_복구_흐름() {
        // Phase 1: Redis 장애로 CLOSED → OPEN
        chaosState.setRedisBlocked(true);
        for (int i = 1; i <= 10; i++) {
            service.reserve(concertId, (long) i);
        }
        assertThat(redisLockCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Phase 2: Redis 복구 시뮬레이션 → 수동으로 HALF_OPEN 전환
        // (실제는 waitDurationInOpenState=10s 후 자동 전환 — 테스트에서는 강제 전환)
        chaosState.setRedisBlocked(false);
        redisLockCircuitBreaker.transitionToHalfOpenState();
        assertThat(redisLockCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // Phase 3: HALF_OPEN에서 permittedNumberOfCallsInHalfOpenState(3)번 성공
        for (int i = 11; i <= 13; i++) {
            service.reserve(concertId, (long) i);
        }

        // 성공률 100% > (1 - failureRateThreshold=50%) → CLOSED 복구
        assertThat(redisLockCircuitBreaker.getState())
                .as("HALF_OPEN 3회 성공 후 CLOSED 복구되어야 함")
                .isEqualTo(CircuitBreaker.State.CLOSED);

        System.out.printf("%n=== CB 상태 전환 검증 ===%n");
        System.out.printf("CLOSED → OPEN → HALF_OPEN → CLOSED ✅%n");
        System.out.printf("Redis 경로: %d / 폴백 경로: %d%n",
                statsHolder.getRedisPathCount(), statsHolder.getFallbackPathCount());
    }
}
