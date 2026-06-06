package com.example.ticketing;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.global.stock.RedisStockRepository;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.repository.ReservationRepository;
import com.example.ticketing.reservation.service.v6.TicketServiceV6;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V6 — Redis 재고 선점 + Kafka 비동기 DB 처리 통합 테스트
 *
 * 목적:
 *   1. Redis DECR로 재고를 원자적으로 선점해 즉시 SUCCESS/FAIL 응답을 반환하는지 검증한다.
 *   2. 재고 초과 시 INCR 복구로 Redis 재고가 음수가 되지 않음을 검증한다.
 *   3. 동시 500명 요청에서 오버부킹이 0건임을 검증한다.
 *   4. Kafka Consumer가 성공 건에 한해 DB 예약 레코드를 저장함을 검증한다.
 *   5. 최종 Redis 재고와 DB 재고가 일치함을 검증한다.
 *
 * 실행 환경: MySQL, Redis, Kafka 실행 필요
 *   Kafka 기동: docker-compose -f kafka-docker-compose.yml up -d
 */
@SpringBootTest
class ConcurrencyTestV6 {

    private static final int    INITIAL_STOCK    = 100;
    private static final int    THREAD_COUNT     = 500;
    private static final String STOCK_KEY_PREFIX = "stock:concert:";

    @Autowired private TicketServiceV6       ticketServiceV6;
    @Autowired private ConcertRepository     concertRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private RedisStockRepository  redisStockRepository;
    @Autowired private StringRedisTemplate   redisTemplate;

    private Long concertId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        concertRepository.deleteAll();
        Concert concert = concertRepository.save(Concert.create("V6 테스트 공연", INITIAL_STOCK));
        concertId = concert.getId();
        redisStockRepository.initStock(concertId, INITIAL_STOCK);
    }

    // ── 1. 단건 정상 예약 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("단건 예약 → 즉시 SUCCESS 응답, Redis 재고 1 감소, Kafka 처리 후 DB 저장 확인")
    void 단건_예약_정상_동작() throws InterruptedException {
        ReserveResponse response = ticketServiceV6.reserve(concertId, 1L);

        // 즉시 SUCCESS 응답 (비동기이므로 reservationId는 null)
        assertThat(response.status()).isEqualTo(ReservationStatus.SUCCESS);
        assertThat(response.reservationId()).isNull();

        // Redis 재고 즉시 감소 확인
        long redisStock = getRedisStock(concertId);
        assertThat(redisStock).isEqualTo(INITIAL_STOCK - 1);

        // Kafka Consumer 처리 대기 후 DB 저장 확인
        awaitReservationCount(1);
        int reservationCount = reservationRepository.countByConcertId(concertId);
        assertThat(reservationCount).isEqualTo(1);

        int dbStock = concertRepository.findById(concertId).orElseThrow().getStock();
        assertThat(dbStock).isEqualTo(INITIAL_STOCK - 1);

        System.out.println("\n=== V6 단건 예약 ===");
        System.out.println("즉시 SUCCESS 응답 ✅");
        System.out.println("Redis 재고: " + redisStock + " ✅");
        System.out.println("DB 예약 건수: " + reservationCount + " ✅");
    }

    // ── 2. 재고 0 상태에서 예약 시도 ─────────────────────────────────────────────

    @Test
    @DisplayName("재고 0인 공연 예약 → 즉시 SoldOutException, Redis INCR 복구로 음수 미발생")
    void 재고_0에서_예약시_즉시_SoldOut_및_Redis_복구() {
        Concert soldOut = concertRepository.save(Concert.create("매진 공연", 0));
        Long soldOutId  = soldOut.getId();
        redisStockRepository.initStock(soldOutId, 0);

        assertThatThrownBy(() -> ticketServiceV6.reserve(soldOutId, 1L))
                .isInstanceOf(SoldOutException.class);

        // DECR로 -1이 됐다가 INCR로 복구 → 0 유지 확인
        long redisStock = getRedisStock(soldOutId);
        assertThat(redisStock)
                .as("Redis 재고 음수 발생 — INCR 복구 실패")
                .isGreaterThanOrEqualTo(0);

        System.out.println("\n=== V6 재고 0 예약 시도 ===");
        System.out.println("SoldOutException 즉시 반환 ✅");
        System.out.println("Redis 재고 복구 후: " + redisStock + " ✅");
    }

    // ── 3. 동시 500명 — Redis 선점으로 오버부킹 0건 검증 ─────────────────────────

    @Test
    @DisplayName("동시 500명 / 재고 100개 → Redis 선점으로 오버부킹 0건, Kafka 처리 후 DB 일치")
    void 동시_500명_오버부킹_0건_및_DB_일치() throws InterruptedException {
        ExecutorService executor    = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch  latch       = new CountDownLatch(THREAD_COUNT);
        AtomicInteger   successCount = new AtomicInteger(0);
        AtomicInteger   failCount    = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            long userId = i + 1;
            executor.submit(() -> {
                try {
                    ticketServiceV6.reserve(concertId, userId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Redis 선점 결과 즉시 검증
        long redisStock = getRedisStock(concertId);

        assertThat(successCount.get())
                .as("성공 수가 초기 재고 초과 — Redis 선점 실패")
                .isLessThanOrEqualTo(INITIAL_STOCK);
        assertThat(redisStock)
                .as("Redis 재고 음수 발생")
                .isGreaterThanOrEqualTo(0);
        assertThat(successCount.get() + redisStock)
                .as("성공 수 + Redis 재고 ≠ 초기 재고")
                .isEqualTo(INITIAL_STOCK);

        // Kafka Consumer 처리 완료 대기 후 DB 검증
        awaitReservationCount(successCount.get());
        int reservationCount = reservationRepository.countByConcertId(concertId);
        int dbStock          = concertRepository.findById(concertId).orElseThrow().getStock();

        printResult(successCount.get(), failCount.get(), redisStock, reservationCount, dbStock);

        assertThat(reservationCount)
                .as("DB 예약 건수가 성공 수와 불일치")
                .isEqualTo(successCount.get());
        assertThat(dbStock)
                .as("DB 재고 음수 발생")
                .isGreaterThanOrEqualTo(0);
        assertThat((long) dbStock)
                .as("Redis 재고와 DB 재고 불일치")
                .isEqualTo(redisStock);
    }

    // ── 4. 재고 소진 후 추가 요청 — 즉시 실패, DB 추가 저장 없음 ─────────────────

    @Test
    @DisplayName("재고 소진 후 추가 요청 → 즉시 SoldOutException, DB 예약 건수 초과 없음")
    void 재고_소진_후_추가_요청_즉시_실패() throws InterruptedException {
        for (int i = 1; i <= INITIAL_STOCK; i++) {
            ticketServiceV6.reserve(concertId, (long) i);
        }

        assertThatThrownBy(() -> ticketServiceV6.reserve(concertId, 999L))
                .isInstanceOf(SoldOutException.class);

        long redisStock = getRedisStock(concertId);
        assertThat(redisStock).isEqualTo(0);

        awaitReservationCount(INITIAL_STOCK);
        int reservationCount = reservationRepository.countByConcertId(concertId);
        assertThat(reservationCount).isEqualTo(INITIAL_STOCK);

        System.out.println("\n=== V6 재고 소진 후 추가 요청 ===");
        System.out.println("추가 요청 즉시 SoldOutException ✅");
        System.out.println("DB 예약 건수: " + reservationCount + " (초과 없음) ✅");
    }

    // ── 헬퍼 메서드 ──────────────────────────────────────────────────────────────

    private long getRedisStock(Long concertId) {
        String value = redisTemplate.opsForValue().get(STOCK_KEY_PREFIX + concertId);
        return value == null ? 0L : Long.parseLong(value);
    }

    // Kafka 비동기 처리 완료를 폴링으로 대기 (최대 30초)
    private void awaitReservationCount(int expectedCount) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            if (reservationRepository.countByConcertId(concertId) >= expectedCount) return;
            Thread.sleep(500);
        }
        System.out.println("⚠️ Kafka 처리 대기 타임아웃 (30초 초과)");
    }

    private void printResult(int success, int fail, long redisStock,
                             int reservationCount, int dbStock) {
        System.out.println("\n=== V6 Redis 선점 + Kafka 비동기 테스트 결과 ===");
        System.out.println("총 요청 수      : " + THREAD_COUNT);
        System.out.println("성공 수         : " + success);
        System.out.println("실패 수         : " + fail);
        System.out.println("Redis 최종 재고 : " + redisStock);
        System.out.println("DB 예약 건수    : " + reservationCount);
        System.out.println("DB 최종 재고    : " + dbStock);
        System.out.println("오버부킹        : " + (reservationCount > INITIAL_STOCK
                ? "발생 (" + (reservationCount - INITIAL_STOCK) + "건) ❌"
                : "없음 ✅"));
        System.out.println("Redis-DB 재고 일치: " + (redisStock == dbStock ? "✅" : "❌"));
    }
}
