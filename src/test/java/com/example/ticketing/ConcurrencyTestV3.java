package com.example.ticketing;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.global.exception.ReservationFailedException;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.repository.ReservationRepository;
import com.example.ticketing.reservation.service.v3.TicketServiceV3;
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
class ConcurrencyTestV3 {

    private static final int INITIAL_STOCK = 100;
    private static final int THREAD_COUNT  = 500;

    @Autowired private TicketServiceV3       ticketServiceV3;
    @Autowired private ConcertRepository     concertRepository;
    @Autowired private ReservationRepository reservationRepository;

    private Long concertId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        concertRepository.deleteAll();
        Concert concert = concertRepository.save(Concert.create("V3 테스트 공연", INITIAL_STOCK));
        concertId = concert.getId();
    }

    @Test
    @DisplayName("단건 예약 → SUCCESS 응답, 재고 1 감소")
    void 단건_예약_정상_동작() {
        ReserveResponse response = ticketServiceV3.reserve(concertId, 1L);

        assertThat(response.status()).isEqualTo(ReservationStatus.SUCCESS);
        assertThat(response.reservationId()).isNotNull();

        int finalStock = concertRepository.findById(concertId).orElseThrow().getStock();
        assertThat(finalStock).isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    @DisplayName("재고 0인 공연 예약 → SoldOutException 발생")
    void 재고_0에서_예약시_SoldOut_예외() {
        Concert soldOut = concertRepository.save(Concert.create("매진 공연", 0));

        assertThatThrownBy(() -> ticketServiceV3.reserve(soldOut.getId(), 1L))
                .isInstanceOf(SoldOutException.class);
    }

    @Test
    @DisplayName("동시 500명 / 재고 100개 → 오버부킹 0건 (재시도로 정합성 보장)")
    void 동시_500명_오버부킹_0건_확인() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            long userId = i + 1;
            executor.submit(() -> {
                try {
                    ticketServiceV3.reserve(concertId, userId);
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

        printResult("V3 Optimistic Lock", THREAD_COUNT, successCount.get(),
                failCount.get(), finalStock, reservationCount);
        System.out.println("※ 로그에서 '[V3] 낙관적 락 충돌, 재시도' 횟수 확인 — V2 대비 재시도 오버헤드 측정 가능");

        assertThat(reservationCount)
                .as("오버부킹 발생")
                .isLessThanOrEqualTo(INITIAL_STOCK);
        assertThat(finalStock)
                .as("재고 음수 — 정합성 위반")
                .isGreaterThanOrEqualTo(0);
        assertThat(successCount.get() + finalStock)
                .as("성공 수 + 남은 재고 ≠ 초기 재고")
                .isEqualTo(INITIAL_STOCK);
    }

    @Test
    @DisplayName("재고 1개 / 동시 50명 → 1건만 성공, 나머지는 재시도 초과 또는 매진 예외")
    void 고경합_단일_재고_재시도_초과_예외_발생() throws InterruptedException {
        Concert singleStock = concertRepository.save(Concert.create("단일 재고 공연", 1));
        Long singleConcertId = singleStock.getId();

        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        AtomicInteger successCount       = new AtomicInteger(0);
        AtomicInteger retryExceededCount = new AtomicInteger(0);
        AtomicInteger soldOutCount       = new AtomicInteger(0);
        AtomicInteger otherCount         = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            long userId = i + 1;
            executor.submit(() -> {
                try {
                    ticketServiceV3.reserve(singleConcertId, userId);
                    successCount.incrementAndGet();
                } catch (ReservationFailedException e) {
                    retryExceededCount.incrementAndGet();
                } catch (SoldOutException e) {
                    soldOutCount.incrementAndGet();
                } catch (Exception e) {
                    otherCount.incrementAndGet();
                    System.out.println("[기타 예외] " + e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(120, TimeUnit.SECONDS);
        executor.shutdown();

        int finalStock       = concertRepository.findById(singleConcertId).orElseThrow().getStock();
        int reservationCount = reservationRepository.countByConcertId(singleConcertId);

        System.out.println("\n=== V3 고경합 단일 재고 테스트 (스레드 " + threads + "개 / 재고 1개) ===");
        System.out.println("성공               : " + successCount.get());
        System.out.println("재시도 초과(ReservationFailed): " + retryExceededCount.get());
        System.out.println("매진 예외(SoldOut)  : " + soldOutCount.get());
        System.out.println("기타 예외           : " + otherCount.get());
        System.out.println("최종 재고           : " + finalStock);
        System.out.println("예약 레코드 수       : " + reservationCount);
        System.out.println("※ 높은 충돌률에서 낙관적 락의 ReservationFailedException 발생을 확인하세요.");

        assertThat(successCount.get())
                .as("재고 1개인데 2건 이상 성공 — 오버부킹")
                .isEqualTo(1);

        assertThat(retryExceededCount.get() + soldOutCount.get())
                .as("실패 건수 합계가 49건이어야 함")
                .isEqualTo(threads - 1);

        assertThat(finalStock).isEqualTo(0);
        assertThat(reservationCount).isEqualTo(1);
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
                ? "발생 (" + (reservationCount - INITIAL_STOCK) + "건) ❌"
                : "없음 ✅"));
    }
}
