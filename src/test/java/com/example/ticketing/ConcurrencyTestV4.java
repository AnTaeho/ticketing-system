package com.example.ticketing;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.global.exception.LockAcquisitionFailedException;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.repository.ReservationRepository;
import com.example.ticketing.reservation.service.v4.TicketServiceV4;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ConcurrencyTestV4 {

    private static final int    INITIAL_STOCK   = 100;
    private static final int    THREAD_COUNT    = 500;
    private static final String LOCK_KEY_PREFIX = "lock:concert:";

    @Autowired private TicketServiceV4       ticketServiceV4;
    @Autowired private ConcertRepository     concertRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private StringRedisTemplate   redisTemplate;

    private Long concertId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        concertRepository.deleteAll();
        Concert concert = concertRepository.save(Concert.create("V4 테스트 공연", INITIAL_STOCK));
        concertId = concert.getId();
        redisTemplate.delete(LOCK_KEY_PREFIX + concertId);
    }

    @Test
    @DisplayName("단건 예약 → SUCCESS 응답, 재고 1 감소, Redis 락 키 자동 해제 확인")
    void 단건_예약_정상_동작_및_락_키_해제_확인() {
        ReserveResponse response = ticketServiceV4.reserve(concertId, 1L);

        assertThat(response.status()).isEqualTo(ReservationStatus.SUCCESS);
        assertThat(response.reservationId()).isNotNull();

        int finalStock = concertRepository.findById(concertId).orElseThrow().getStock();
        assertThat(finalStock).isEqualTo(INITIAL_STOCK - 1);

        Boolean keyExists = redisTemplate.hasKey(LOCK_KEY_PREFIX + concertId);
        assertThat(keyExists)
                .as("예약 완료 후에도 Redis 락 키가 남아있음 — finally 블록 미실행 의심")
                .isFalse();
    }

    @Test
    @DisplayName("재고 0인 공연 예약 → SoldOutException, 락 키는 해제됨")
    void 재고_0에서_예약시_SoldOut_예외_후_락_해제() {
        Concert soldOut = concertRepository.save(Concert.create("매진 공연", 0));
        Long soldOutId  = soldOut.getId();

        assertThatThrownBy(() -> ticketServiceV4.reserve(soldOutId, 1L))
                .isInstanceOf(SoldOutException.class);

        Boolean keyExists = redisTemplate.hasKey(LOCK_KEY_PREFIX + soldOutId);
        assertThat(keyExists)
                .as("예외 발생 후 Redis 락 키가 남아있음 — finally 블록 미실행 의심")
                .isFalse();
    }

    @Test
    @DisplayName("동시 500명 / 재고 100개 → 스핀 락으로 오버부킹 0건 보장")
    void 동시_500명_오버부킹_0건_확인() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            long userId = i + 1;
            executor.submit(() -> {
                try {
                    ticketServiceV4.reserve(concertId, userId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(300, TimeUnit.SECONDS);
        executor.shutdown();

        int finalStock       = concertRepository.findById(concertId).orElseThrow().getStock();
        int reservationCount = reservationRepository.countByConcertId(concertId);

        printResult("V4 Lettuce Spin Lock", THREAD_COUNT, successCount.get(),
                failCount.get(), finalStock, reservationCount);
        System.out.println("※ 로그에서 '[V4] 락 대기 중' 횟수 확인 — Redis 폴링 부하 지표");

        assertThat(reservationCount)
                .as("오버부킹 발생")
                .isLessThanOrEqualTo(INITIAL_STOCK);
        assertThat(finalStock)
                .as("재고 음수")
                .isGreaterThanOrEqualTo(0);
        assertThat(successCount.get() + finalStock)
                .as("성공 수 + 남은 재고 ≠ 초기 재고")
                .isEqualTo(INITIAL_STOCK);

        assertThat(redisTemplate.hasKey(LOCK_KEY_PREFIX + concertId))
                .as("테스트 완료 후 Redis 락 키 잔류")
                .isFalse();
    }

    @Test
    @Timeout(value = 15)
    @DisplayName("락 점유 상태에서 MAX_SPIN_COUNT 초과 → LockAcquisitionFailedException 발생")
    void 락이_이미_잡힌_상태에서_스핀_초과_예외() {
        redisTemplate.opsForValue()
                .set(LOCK_KEY_PREFIX + concertId, "held-by-other", Duration.ofSeconds(15));

        assertThatThrownBy(() -> ticketServiceV4.reserve(concertId, 1L))
                .isInstanceOf(LockAcquisitionFailedException.class)
                .hasMessageContaining(String.valueOf(concertId));

        int reservationCount = reservationRepository.countByConcertId(concertId);
        assertThat(reservationCount).isEqualTo(0);

        redisTemplate.delete(LOCK_KEY_PREFIX + concertId);
    }

    @Test
    @Timeout(value = 10)
    @DisplayName("Redis 락 TTL 만료 후 다음 요청이 정상 락 획득 및 예약 성공")
    void 락_TTL_만료_후_다음_요청_정상_처리() throws InterruptedException {
        redisTemplate.opsForValue()
                .set(LOCK_KEY_PREFIX + concertId, "expiring-lock", Duration.ofSeconds(2));

        Thread.sleep(2200);

        ReserveResponse response = ticketServiceV4.reserve(concertId, 1L);

        assertThat(response.status()).isEqualTo(ReservationStatus.SUCCESS);
        assertThat(concertRepository.findById(concertId).orElseThrow().getStock())
                .isEqualTo(INITIAL_STOCK - 1);

        System.out.println("\n=== V4 TTL 만료 후 락 재획득 테스트 ===");
        System.out.println("TTL 만료 후 예약 성공 ✅");
        System.out.println("이 결과는 락 TTL이 적절히 설정되어야 하는 이유를 보여줍니다:");
        System.out.println("  TTL < 실제 트랜잭션 시간 → 만료 후 다른 스레드가 락 획득 → 동시성 문제 재발");
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
