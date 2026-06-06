package com.example.ticketing;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.global.exception.LockAcquisitionFailedException;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.repository.ReservationRepository;
import com.example.ticketing.reservation.service.v5.TicketServiceV5;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V5 — Redisson Pub-Sub Lock 통합 테스트
 *
 * 목적:
 *   1. Pub-Sub 방식 락이 오버부킹을 방지함을 검증한다.
 *   2. waitTime 초과 시 LockAcquisitionFailedException이 발생함을 검증한다.
 *   3. leaseTime 만료 후 다음 요청이 정상 처리됨을 검증한다.
 *   4. V4 대비 Redis 명령 수 차이 확인 (로그 또는 redis-cli INFO stats로 비교).
 *
 * 실행 환경: MySQL, Redis 실행 필요
 */
@SpringBootTest
class ConcurrencyTestV5 {

    private static final int    INITIAL_STOCK   = 100;
    private static final int    THREAD_COUNT    = 500;
    private static final String LOCK_KEY_PREFIX = "lock:concert:";

    @Autowired private TicketServiceV5       ticketServiceV5;
    @Autowired private ConcertRepository     concertRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private RedissonClient        redissonClient;

    private Long concertId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        concertRepository.deleteAll();
        Concert concert = concertRepository.save(Concert.create("V5 테스트 공연", INITIAL_STOCK));
        concertId = concert.getId();
        // 이전 테스트 락 잔류 제거
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + concertId);
        if (lock.isLocked()) {
            lock.forceUnlock();
        }
    }

    // ── 1. 단건 정상 예약 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("단건 예약 → SUCCESS 응답, 재고 1 감소, Redisson 락 자동 해제 확인")
    void 단건_예약_정상_동작_및_락_해제_확인() {
        ReserveResponse response = ticketServiceV5.reserve(concertId, 1L);

        assertThat(response.status()).isEqualTo(ReservationStatus.SUCCESS);
        assertThat(response.reservationId()).isNotNull();

        int finalStock = concertRepository.findById(concertId).orElseThrow().getStock();
        assertThat(finalStock).isEqualTo(INITIAL_STOCK - 1);

        // 예약 완료 후 Redisson 락이 해제되어야 함
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + concertId);
        assertThat(lock.isLocked())
                .as("예약 완료 후에도 Redisson 락이 잠긴 상태 — releaseLockSafely 미실행 의심")
                .isFalse();
    }

    // ── 2. 재고 0 상태에서 예약 시도 ─────────────────────────────────────────────

    @Test
    @DisplayName("재고 0인 공연 예약 → SoldOutException, 락은 정상 해제됨")
    void 재고_0에서_예약시_SoldOut_예외_후_락_해제() {
        Concert soldOut = concertRepository.save(Concert.create("매진 공연", 0));
        Long soldOutId  = soldOut.getId();

        assertThatThrownBy(() -> ticketServiceV5.reserve(soldOutId, 1L))
                .isInstanceOf(SoldOutException.class);

        // 예외 후에도 finally에서 락이 해제되어야 함
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + soldOutId);
        assertThat(lock.isLocked())
                .as("SoldOutException 발생 후 Redisson 락이 잠긴 상태")
                .isFalse();
    }

    // ── 3. 동시 500명 — 오버부킹 0건 검증 ────────────────────────────────────────

    @Test
    @DisplayName("동시 500명 / 재고 100개 → Pub-Sub 락으로 오버부킹 0건 보장 (V4 대비 Redis 부하 감소)")
    void 동시_500명_오버부킹_0건_확인() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            long userId = i + 1;
            executor.submit(() -> {
                try {
                    ticketServiceV5.reserve(concertId, userId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(120, TimeUnit.SECONDS);
        executor.shutdown();

        int finalStock       = concertRepository.findById(concertId).orElseThrow().getStock();
        int reservationCount = reservationRepository.countByConcertId(concertId);

        printResult("V5 Redisson Pub-Sub", THREAD_COUNT, successCount.get(),
                failCount.get(), finalStock, reservationCount);
        System.out.println("※ V4와 동일 조건 후 redis-cli INFO stats | grep total_commands_processed 비교");
        System.out.println("   V4: Spin 폴링으로 Redis 명령 수 폭발 / V5: Pub-Sub으로 불필요 폴링 제거");

        // 핵심 검증: 오버부킹 0건
        assertThat(reservationCount)
                .as("오버부킹 발생")
                .isLessThanOrEqualTo(INITIAL_STOCK);
        assertThat(finalStock)
                .as("재고 음수")
                .isGreaterThanOrEqualTo(0);
        assertThat(successCount.get() + finalStock)
                .as("성공 수 + 남은 재고 ≠ 초기 재고")
                .isEqualTo(INITIAL_STOCK);

        // 테스트 완료 후 Redisson 락 해제 확인
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + concertId);
        assertThat(lock.isLocked())
                .as("테스트 완료 후 Redisson 락 잔류")
                .isFalse();
    }

    // ── 4. waitTime 초과 — LockAcquisitionFailedException 검증 ───────────────────

    @Test
    @Timeout(value = 15)  // waitTime=5초 + 스레드 오버헤드 + 여유
    @DisplayName("락 보유 중 waitTime(5초) 초과 → LockAcquisitionFailedException 발생")
    void waitTime_초과시_LockAcquisitionFailed_예외() throws Exception {
        RLock heldLock = redissonClient.getLock(LOCK_KEY_PREFIX + concertId);
        // 테스트 스레드가 락 보유 (leaseTime=10초 — waitTime=5초보다 길게)
        heldLock.lock(10, TimeUnit.SECONDS);

        // Redisson RLock은 재진입(reentrant) 락이므로 같은 스레드에서 tryLock을 호출하면
        // 즉시 성공한다. 별도 스레드에서 호출해야 실제 대기/실패 시나리오를 검증할 수 있다.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> ticketServiceV5.reserve(concertId, 1L));
        executor.shutdown();

        try {
            assertThatThrownBy(() -> future.get(7, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(LockAcquisitionFailedException.class);

            // 락 획득 실패 → 예약 저장 없음
            int reservationCount = reservationRepository.countByConcertId(concertId);
            assertThat(reservationCount).isEqualTo(0);

            System.out.println("\n=== V5 waitTime 초과 테스트 ===");
            System.out.println("waitTime=5초 초과 → LockAcquisitionFailedException ✅");
        } finally {
            if (heldLock.isHeldByCurrentThread()) {
                heldLock.unlock();
            }
        }
    }

    // ── 5. leaseTime 만료 후 정상 처리 검증 ──────────────────────────────────────

    @Test
    @Timeout(value = 10)
    @DisplayName("Redisson leaseTime 만료 후 다음 요청이 정상 락 획득 및 예약 성공")
    void leaseTime_만료_후_다음_요청_정상_처리() throws InterruptedException {
        RLock expiringLock = redissonClient.getLock(LOCK_KEY_PREFIX + concertId);
        // 2초 후 자동 해제되는 락
        expiringLock.lock(2, TimeUnit.SECONDS);

        // 2.2초 대기 → leaseTime 만료
        Thread.sleep(2200);

        // 만료 후 정상 예약
        ReserveResponse response = ticketServiceV5.reserve(concertId, 1L);

        assertThat(response.status()).isEqualTo(ReservationStatus.SUCCESS);
        assertThat(concertRepository.findById(concertId).orElseThrow().getStock())
                .isEqualTo(INITIAL_STOCK - 1);

        System.out.println("\n=== V5 leaseTime 만료 후 락 재획득 테스트 ===");
        System.out.println("leaseTime 만료 후 예약 성공 ✅");
        System.out.println("leaseTime을 실제 트랜잭션 소요시간보다 충분히 크게 설정해야 합니다.");
        System.out.println("이 프로젝트: LOCK_LEASE_SECONDS=3, 트랜잭션 예상 소요=~10-50ms → 여유 있음");
    }

    // ── 출력 헬퍼 ────────────────────────────────────────────────────────────────

    private void printResult(String label, int total, int success, int fail,
                             int finalStock, int reservationCount) {
        System.out.println("\n=== " + label + " 테스트 결과 ===");
        System.out.println("총 요청 수     : " + total);
        System.out.println("성공 수        : " + success);
        System.out.println("실패 수        : " + fail);
        System.out.println("최종 재고      : " + finalStock);
        System.out.println("예약 레코드 수  : " + reservationCount);
        System.out.println("오버부킹       : " + (reservationCount > INITIAL_STOCK
                ? "발생 (" + (reservationCount - INITIAL_STOCK) + "건) ❌"
                : "없음 ✅"));
    }
}
