package com.example.ticketing;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.repository.ReservationRepository;
import com.example.ticketing.reservation.service.v1.TicketServiceV1;
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

@SpringBootTest
class ConcurrencyTestV1 {

    private static final int INITIAL_STOCK  = 100;
    private static final int THREAD_COUNT   = 500;

    @Autowired private TicketServiceV1      ticketServiceV1;
    @Autowired private ConcertRepository    concertRepository;
    @Autowired private ReservationRepository reservationRepository;

    private Long concertId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        concertRepository.deleteAll();
        Concert concert = concertRepository.save(Concert.create("V1 테스트 공연", INITIAL_STOCK));
        concertId = concert.getId();
    }

    @Test
    @DisplayName("단건 예약 → SUCCESS 응답, 재고 1 감소")
    void 단건_예약_정상_동작() {
        ReserveResponse response = ticketServiceV1.reserve(concertId, 1L);

        assertThat(response.status()).isEqualTo(ReservationStatus.SUCCESS);
        assertThat(response.reservationId()).isNotNull();

        int finalStock = concertRepository.findById(concertId).orElseThrow().getStock();
        assertThat(finalStock).isEqualTo(INITIAL_STOCK - 1);

        int reservationCount = reservationRepository.countByConcertId(concertId);
        assertThat(reservationCount).isEqualTo(1);
    }

    @Test
    @DisplayName("재고 0인 공연 예약 → SoldOutException 즉시 발생")
    void 재고_0에서_예약시_SoldOut_예외() {
        Concert soldOut = concertRepository.save(Concert.create("매진 공연", 0));

        assertThatThrownBy(() -> ticketServiceV1.reserve(soldOut.getId(), 1L))
                .isInstanceOf(SoldOutException.class);
    }

    @Test
    @DisplayName("동시 500명 / 재고 100개 → Lock 없으므로 오버부킹 발생 (Race Condition 증명)")
    void 동시_500명_오버부킹_발생_확인() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            long userId = i + 1;
            executor.submit(() -> {
                try {
                    ticketServiceV1.reserve(concertId, userId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        int finalStock        = concertRepository.findById(concertId).orElseThrow().getStock();
        int reservationCount  = reservationRepository.countByConcertId(concertId);
        boolean isOverBooked  = reservationCount > INITIAL_STOCK;

        printResult("V1 No Lock", THREAD_COUNT, successCount.get(), failCount.get(),
                finalStock, reservationCount);

        assertThat(successCount.get() + failCount.get()).isEqualTo(THREAD_COUNT);

        if (isOverBooked) {
            System.out.println("✅ 오버부킹 발생 확인 — Race Condition 재현 성공: "
                    + (reservationCount - INITIAL_STOCK) + "건 초과");
        } else {
            System.out.println("⚠️  이번 실행에서는 오버부킹이 발생하지 않았습니다. "
                    + "(스레드 실행 순서에 따라 달라질 수 있음)");
        }
    }

    @Test
    @DisplayName("동시 200명 / 재고 100개 → 오버부킹 가능성 (비결정적)")
    void 동시_200명_재고_100개_정합성_불보장() throws InterruptedException {
        int threads = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            long userId = i + 1;
            executor.submit(() -> {
                try {
                    ticketServiceV1.reserve(concertId, userId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        int finalStock       = concertRepository.findById(concertId).orElseThrow().getStock();
        int reservationCount = reservationRepository.countByConcertId(concertId);

        printResult("V1 No Lock (200명/100재고)", threads, successCount.get(),
                failCount.get(), finalStock, reservationCount);

        assertThat(successCount.get() + failCount.get()).isEqualTo(threads);

        System.out.printf("재고 정합성: %s%n",
                finalStock >= 0 ? "재고 ≥ 0 (이번은 정상)" : "재고 < 0 ⚠️ (정합성 위반)");
    }

    private void printResult(String label, int total, int success, int fail,
                             int finalStock, int reservationCount) {
        System.out.println("\n=== " + label + " 테스트 결과 ===");
        System.out.println("총 요청 수     : " + total);
        System.out.println("성공 수        : " + success);
        System.out.println("실패 수        : " + fail);
        System.out.println("최종 재고      : " + finalStock);
        System.out.println("예약 레코드 수  : " + reservationCount);
        System.out.println("오버부킹       : " + (reservationCount > INITIAL_STOCK
                ? "발생 (" + (reservationCount - INITIAL_STOCK) + "건)" : "없음"));
    }
}
