package com.example.ticketing;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.reservation.repository.ReservationRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DeadlockTest {

    @TestConfiguration
    static class Config {
        @Bean
        DeadlockSimulator deadlockSimulator(ConcertRepository concertRepository) {
            return new DeadlockSimulator(concertRepository);
        }
    }

    static class DeadlockSimulator {

        private final ConcertRepository concertRepository;

        DeadlockSimulator(ConcertRepository concertRepository) {
            this.concertRepository = concertRepository;
        }

        @Transactional(timeout = 3)
        public void lockInOrder(Long firstId, Long secondId,
                                CountDownLatch readyLatch, CountDownLatch startLatch) {
            concertRepository.findByIdWithPessimisticLock(firstId);
            readyLatch.countDown();
            try {
                startLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concertRepository.findByIdWithPessimisticLock(secondId);
        }
    }

    @Autowired
    private DeadlockSimulator deadlockSimulator;

    @Autowired
    private ConcertRepository concertRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    private Long concertId1;
    private Long concertId2;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        concertRepository.deleteAll();
        concertId1 = concertRepository.save(Concert.create("공연 A", 100)).getId();
        concertId2 = concertRepository.save(Concert.create("공연 B", 100)).getId();
    }

    @Test
    void 데드락_발생_후_타임아웃으로_해소() throws InterruptedException {
        CountDownLatch readyA  = new CountDownLatch(1);
        CountDownLatch readyB  = new CountDownLatch(1);
        CountDownLatch startAB = new CountDownLatch(2);
        AtomicReference<Exception> exceptionA = new AtomicReference<>();
        AtomicReference<Exception> exceptionB = new AtomicReference<>();

        CountDownLatch done = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> {
            try {
                deadlockSimulator.lockInOrder(concertId1, concertId2, readyA,
                        newStartLatch(readyA, readyB, startAB));
            } catch (Exception e) {
                exceptionA.set(e);
                System.out.println("[스레드 A] 예외 발생: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        executor.submit(() -> {
            try {
                deadlockSimulator.lockInOrder(concertId2, concertId1, readyB,
                        newStartLatch(readyA, readyB, startAB));
            } catch (Exception e) {
                exceptionB.set(e);
                System.out.println("[스레드 B] 예외 발생: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        done.await();
        executor.shutdown();

        System.out.println("=== 데드락 시나리오 테스트 결과 ===");
        System.out.println("스레드 A 예외: " + (exceptionA.get() != null ? exceptionA.get().getClass().getSimpleName() : "없음"));
        System.out.println("스레드 B 예외: " + (exceptionB.get() != null ? exceptionB.get().getClass().getSimpleName() : "없음"));
        System.out.println("→ 둘 중 하나 이상이 예외를 받으면 데드락이 감지/해소된 것");

        assertThat(exceptionA.get() != null || exceptionB.get() != null).isTrue();
    }

    private CountDownLatch newStartLatch(CountDownLatch readyA, CountDownLatch readyB,
                                         CountDownLatch startAB) {
        return new CountDownLatch(0) {
            @Override
            public void await() throws InterruptedException {
                startAB.countDown();
                readyA.await();
                readyB.await();
            }
        };
    }
}
