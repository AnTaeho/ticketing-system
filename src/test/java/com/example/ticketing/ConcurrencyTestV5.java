package com.example.ticketing;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.reservation.repository.ReservationRepository;
import com.example.ticketing.reservation.service.TicketServiceV5;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConcurrencyTestV5 {

    private static final int THREAD_COUNT = 500;
    private static final int INITIAL_STOCK = 100;

    @Autowired
    private TicketServiceV5 ticketServiceV5;

    @Autowired
    private ConcertRepository concertRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    private Long concertId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        concertRepository.deleteAll();
        Concert concert = concertRepository.save(Concert.create("테스트 공연", INITIAL_STOCK));
        concertId = concert.getId();
    }

    @Test
    void v5_동시_500명_오버부킹_0건_확인() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

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

        latch.await();
        executor.shutdown();

        int finalStock = concertRepository.findById(concertId).get().getStock();
        int reservationCount = reservationRepository.countByConcertId(concertId);

        System.out.println("=== V5 Redisson Pub-Sub 동시성 테스트 결과 ===");
        System.out.println("총 요청 수    : " + THREAD_COUNT);
        System.out.println("성공 수       : " + successCount.get());
        System.out.println("실패 수       : " + failCount.get());
        System.out.println("최종 재고     : " + finalStock);
        System.out.println("예약 레코드 수: " + reservationCount);
        System.out.println("오버부킹 여부 : " + (reservationCount > INITIAL_STOCK
                ? "발생 (" + (reservationCount - INITIAL_STOCK) + "건)"
                : "없음 ✅"));
        System.out.println("※ V4와 Redis total_commands_processed 비교 시 폴링 감소 확인 가능");

        assertThat(reservationCount).isLessThanOrEqualTo(INITIAL_STOCK);
        assertThat(finalStock).isGreaterThanOrEqualTo(0);
    }
}
