package com.example.ticketing;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.repository.ReservationRepository;
import com.example.ticketing.reservation.service.v2.TicketServiceV2;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V2 — Pessimistic Lock 통합 테스트
 *
 * 목적: SELECT FOR UPDATE로 오버부킹이 원천 차단됨을 검증한다.
 * 추가 검증: 재고 정확 소진(경계값), 데드락 해소는 DeadlockTest.java 참조.
 */
@SpringBootTest
class ConcurrencyTestV2 {

    private static final int INITIAL_STOCK = 100;
    private static final int THREAD_COUNT  = 500;

    @Autowired private TicketServiceV2       ticketServiceV2;
    @Autowired private ConcertRepository     concertRepository;
    @Autowired private ReservationRepository reservationRepository;

    private Long concertId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        concertRepository.deleteAll();
        Concert concert = concertRepository.save(Concert.create("V2 테스트 공연", INITIAL_STOCK));
        concertId = concert.getId();
    }

    // ── 1. 단건 정상 예약 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("단건 예약 → SUCCESS 응답, 재고 정확히 1 감소")
    void 단건_예약_정상_동작() {
        ReserveResponse response = ticketServiceV2.reserve(concertId, 1L);

        assertThat(response.status()).isEqualTo(ReservationStatus.SUCCESS);
        assertThat(response.reservationId()).isNotNull();

        int finalStock = concertRepository.findById(concertId).orElseThrow().getStock();
        assertThat(finalStock).isEqualTo(INITIAL_STOCK - 1);

        int reservationCount = reservationRepository.countByConcertId(concertId);
        assertThat(reservationCount).isEqualTo(1);
    }

    // ── 2. 재고 0 상태에서 예약 시도 ─────────────────────────────────────────────

    @Test
    @DisplayName("재고 0인 공연 예약 → SoldOutException 즉시 발생")
    void 재고_0에서_예약시_SoldOut_예외() {
        Concert soldOut = concertRepository.save(Concert.create("매진 공연", 0));

        assertThatThrownBy(() -> ticketServiceV2.reserve(soldOut.getId(), 1L))
                .isInstanceOf(SoldOutException.class);
    }

    // ── 3. 동시 500명 — 오버부킹 0건 검증 ────────────────────────────────────────

    @Test
    @DisplayName("동시 500명 / 재고 100개 → 오버부킹 0건, 재고 ≥ 0 보장")
    void 동시_500명_오버부킹_0건_확인() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            long userId = i + 1;
            executor.submit(() -> {
                try {
                    ticketServiceV2.reserve(concertId, userId);
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

        printResult("V2 Pessimistic Lock", THREAD_COUNT, successCount.get(),
                failCount.get(), finalStock, reservationCount);

        // 핵심 검증: 오버부킹 0건
        assertThat(reservationCount)
                .as("오버부킹 발생 — 예약 수가 초기 재고를 초과함")
                .isLessThanOrEqualTo(INITIAL_STOCK);

        // 재고는 음수가 될 수 없음
        assertThat(finalStock)
                .as("재고가 음수가 됨 — 정합성 위반")
                .isGreaterThanOrEqualTo(0);

        // 성공 건수 + 남은 재고 = 초기 재고 (정확한 재고 차감)
        assertThat(successCount.get() + finalStock)
                .as("성공 예약 수 + 남은 재고가 초기 재고와 일치하지 않음")
                .isEqualTo(INITIAL_STOCK);
    }

    // ── 4. 재고 정확히 소진 경계값 ───────────────────────────────────────────────

    @Test
    @DisplayName("동시 100명 / 재고 100개 → 정확히 100건 성공, 재고 0 소진")
    void 재고_정확히_소진_경계값_검증() throws InterruptedException {
        int exactThreads = INITIAL_STOCK;  // 재고와 동일한 수의 요청
        ExecutorService executor = Executors.newFixedThreadPool(exactThreads);
        CountDownLatch latch = new CountDownLatch(exactThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        for (int i = 0; i < exactThreads; i++) {
            long userId = i + 1;
            executor.submit(() -> {
                try {
                    ticketServiceV2.reserve(concertId, userId);
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

        System.out.println("\n=== V2 경계값 테스트 (재고=요청수=" + exactThreads + ") ===");
        System.out.println("성공: " + successCount.get() + " / 실패: " + failCount.get());
        System.out.println("최종 재고: " + finalStock + " / 예약 수: " + reservationCount);

        // 재고와 정확히 같은 수의 요청 → 모두 성공해야 함
        assertThat(successCount.get()).isEqualTo(INITIAL_STOCK);
        assertThat(failCount.get()).isEqualTo(0);
        assertThat(finalStock).isEqualTo(0);
        assertThat(reservationCount).isEqualTo(INITIAL_STOCK);
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
