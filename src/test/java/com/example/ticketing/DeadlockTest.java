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

    // ── 데드락 유발용 시뮬레이터 (테스트 전용 Bean) ──────────────────────────────
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

        // timeout=3: 3초 내 락 획득 못 하면 TransactionTimedOutException → 데드락 해소
        @Transactional(timeout = 3)
        public void lockInOrder(Long firstId, Long secondId,
                                CountDownLatch readyLatch, CountDownLatch startLatch) {
            concertRepository.findByIdWithPessimisticLock(firstId); // 첫 번째 락 획득
            readyLatch.countDown();                                  // 락 획득 알림
            try {
                startLatch.await();                                  // 상대 스레드도 준비될 때까지 대기
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concertRepository.findByIdWithPessimisticLock(secondId); // 두 번째 락 시도 → 데드락!
        }
    }
    // ─────────────────────────────────────────────────────────────────────────────

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
        CountDownLatch readyA  = new CountDownLatch(1); // A가 첫 번째 락 획득 완료
        CountDownLatch readyB  = new CountDownLatch(1); // B가 첫 번째 락 획득 완료
        CountDownLatch startAB = new CountDownLatch(2); // 두 스레드 모두 준비됐을 때 출발
        AtomicReference<Exception> exceptionA = new AtomicReference<>();
        AtomicReference<Exception> exceptionB = new AtomicReference<>();

        CountDownLatch done = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 A: concert1 → concert2 순서로 잠금
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

        // 스레드 B: concert2 → concert1 순서로 잠금 (반대 순서 → 데드락 조건)
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

        // MySQL 데드락 감지 또는 트랜잭션 타임아웃으로 최소 하나는 예외 발생해야 함
        assertThat(exceptionA.get() != null || exceptionB.get() != null).isTrue();
    }

    // 두 스레드 모두 첫 번째 락을 잡은 뒤 동시에 두 번째 락을 시도하도록 동기화하는 래치
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
