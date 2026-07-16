package com.example.ticketing;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.global.exception.LockAcquisitionFailedException;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.global.stock.RedisStockRepository;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.repository.ReservationRepository;
import com.example.ticketing.waitingroom.dto.QueueTokenResponse;
import com.example.ticketing.waitingroom.repository.QueueRedisRepository;
import com.example.ticketing.waitingroom.service.QueueCommandService;
import com.example.ticketing.waitingroom.service.ReservationService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

import static com.example.ticketing.waitingroom.WaitingRoomConst.PROCESSING_QUEUE_SIZE;
import static com.example.ticketing.waitingroom.WaitingRoomConst.PROCESSING_KEY_PREFIX;
import static com.example.ticketing.waitingroom.WaitingRoomConst.WAITING_KEY_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ConcurrencyTestWaitingRoom {

    private static final int INITIAL_STOCK = 100;
    private static final int THREAD_COUNT  = 500;

    @Autowired private QueueCommandService   queueCommandService;
    @Autowired private ReservationService    reservationService;
    @Autowired private ConcertRepository     concertRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private RedisStockRepository  redisStockRepository;
    @Autowired private QueueRedisRepository  queueRedisRepository;
    @Autowired private StringRedisTemplate   redisTemplate;

    private Long concertId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        concertRepository.deleteAll();
        Concert concert = concertRepository.save(Concert.create("WaitingRoom 테스트 공연", INITIAL_STOCK));
        concertId = concert.getId();
        redisStockRepository.initStock(concertId, INITIAL_STOCK);
        clearQueues(concertId);
    }

    @Test
    @DisplayName("처리열 토큰 발급 → reserve() 즉시 PENDING → 비동기 DB 저장 완료 확인")
    void 단건_정상_예약_처리열_즉시_입장() throws InterruptedException {
        QueueTokenResponse tokenResp = queueCommandService.issueTokenAndEnqueue(1L, concertId);
        assertThat(tokenResp.status()).isEqualTo("PROCESSING");

        ReserveResponse response = reservationService.reserve(concertId, 1L, tokenResp.token());

        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(getRedisStock(concertId)).isEqualTo(INITIAL_STOCK - 1);

        assertThat(queueRedisRepository.isInProcessingQueue(concertId, tokenResp.token())).isFalse();

        awaitReservationCount(1);
        assertThat(reservationRepository.countByConcertId(concertId)).isEqualTo(1);

        System.out.println("\n=== WaitingRoom 단건 예약 ===");
        System.out.println("처리열 즉시 입장 ✅  |  PENDING 즉시 응답 ✅  |  DB 저장 완료 ✅");
    }

    @Test
    @DisplayName("처리열에 없는 토큰으로 reserve() → LockAcquisitionFailedException, 재고 변화 없음")
    void 미등록_토큰_예약시_즉시_차단() {
        String unknownToken = UUID.randomUUID().toString();

        assertThatThrownBy(() -> reservationService.reserve(concertId, 1L, unknownToken))
                .isInstanceOf(LockAcquisitionFailedException.class);

        assertThat(getRedisStock(concertId)).isEqualTo(INITIAL_STOCK);
        assertThat(reservationRepository.countByConcertId(concertId)).isEqualTo(0);

        System.out.println("\n=== WaitingRoom 미등록 토큰 차단 ===");
        System.out.println("LockAcquisitionFailedException 즉시 반환 ✅  |  재고 불변 ✅");
    }

    @Test
    @DisplayName("재고 0인 공연 + 처리열 토큰 → SoldOutException, INCR 복구로 재고 음수 미발생")
    void 재고_0에서_예약시_SoldOut_및_Redis_복구() {
        Concert soldOut  = concertRepository.save(Concert.create("매진 공연", 0));
        Long soldOutId   = soldOut.getId();
        redisStockRepository.initStock(soldOutId, 0);
        clearQueues(soldOutId);

        QueueTokenResponse tokenResp = queueCommandService.issueTokenAndEnqueue(1L, soldOutId);
        assertThat(tokenResp.status()).isEqualTo("PROCESSING");

        assertThatThrownBy(() -> reservationService.reserve(soldOutId, 1L, tokenResp.token()))
                .isInstanceOf(SoldOutException.class);

        assertThat(getRedisStock(soldOutId))
                .as("Redis 재고 음수 발생 — INCR 복구 실패")
                .isGreaterThanOrEqualTo(0);
        assertThat(reservationRepository.countByConcertId(soldOutId)).isEqualTo(0);

        System.out.println("\n=== WaitingRoom 재고 0 예약 ===");
        System.out.println("SoldOutException 즉시 반환 ✅  |  Redis 재고 복구 후 0 ✅");
    }

    @Test
    @DisplayName("동시 500명 중 처리열 200명만 예약 진입, 재고 100개로 오버부킹 0건 보장")
    void 동시_500명_오버부킹_0건_확인() throws InterruptedException {
        List<String> tokens = new ArrayList<>(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            tokens.add(queueCommandService.issueTokenAndEnqueue((long) (i + 1), concertId).token());
        }

        assertThat(getQueueCount(PROCESSING_KEY_PREFIX, concertId)).isEqualTo(PROCESSING_QUEUE_SIZE);
        assertThat(getQueueCount(WAITING_KEY_PREFIX, concertId))
                .isEqualTo(THREAD_COUNT - PROCESSING_QUEUE_SIZE);

        ExecutorService executor     = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch  latch        = new CountDownLatch(THREAD_COUNT);
        AtomicInteger   successCount = new AtomicInteger(0);
        AtomicInteger   failCount    = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final long   userId = i + 1;
            final String token  = tokens.get(i);
            executor.submit(() -> {
                try {
                    reservationService.reserve(concertId, userId, token);
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

        long redisStock = getRedisStock(concertId);

        assertThat(successCount.get())
                .as("성공 수가 초기 재고 초과 — 오버부킹 발생")
                .isLessThanOrEqualTo(INITIAL_STOCK);
        assertThat(redisStock)
                .as("Redis 재고 음수 발생")
                .isGreaterThanOrEqualTo(0);
        assertThat((long) successCount.get() + redisStock)
                .as("성공 수 + Redis 재고 ≠ 초기 재고")
                .isEqualTo(INITIAL_STOCK);

        awaitReservationCount(successCount.get());
        int reservationCount = reservationRepository.countByConcertId(concertId);

        printConcurrencyResult(successCount.get(), failCount.get(), redisStock, reservationCount);

        assertThat(reservationCount)
                .as("DB 예약 건수가 성공 수와 불일치")
                .isEqualTo(successCount.get());
        assertThat(reservationCount)
                .as("오버부킹 발생")
                .isLessThanOrEqualTo(INITIAL_STOCK);
    }

    @Test
    @DisplayName("처리열 정원(200) 도달 후 대기열 진입 → 슬롯 반환 후 승격 → 예약 성공")
    void 대기열_승격_후_예약_성공() throws InterruptedException {
        List<String> processingTokens = new ArrayList<>();
        for (int i = 0; i < PROCESSING_QUEUE_SIZE; i++) {
            processingTokens.add(
                    queueCommandService.issueTokenAndEnqueue((long) (i + 1), concertId).token());
        }
        assertThat(getQueueCount(PROCESSING_KEY_PREFIX, concertId)).isEqualTo(PROCESSING_QUEUE_SIZE);

        QueueTokenResponse waitingResp = queueCommandService.issueTokenAndEnqueue(201L, concertId);
        assertThat(waitingResp.status()).isEqualTo("WAITING");
        String waitingToken = waitingResp.token();

        assertThatThrownBy(() -> reservationService.reserve(concertId, 201L, waitingToken))
                .isInstanceOf(LockAcquisitionFailedException.class);

        queueRedisRepository.removeFromProcessing(concertId, processingTokens.get(0));
        assertThat(getQueueCount(PROCESSING_KEY_PREFIX, concertId)).isEqualTo(PROCESSING_QUEUE_SIZE - 1);

        int promoted = queueRedisRepository.promoteWaiting(concertId);
        assertThat(promoted).isEqualTo(1);
        assertThat(queueRedisRepository.isInProcessingQueue(concertId, waitingToken)).isTrue();

        ReserveResponse response = reservationService.reserve(concertId, 201L, waitingToken);
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);

        awaitReservationCount(1);
        assertThat(reservationRepository.countByConcertId(concertId)).isEqualTo(1);

        System.out.println("\n=== WaitingRoom Queue 승격 플로우 ===");
        System.out.println("대기열 차단 ✅  |  슬롯 반납 후 승격 ✅  |  승격 후 예약 성공 ✅");
    }

    private void awaitReservationCount(int expectedCount) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            if (reservationRepository.countByConcertId(concertId) >= expectedCount) return;
            Thread.sleep(500);
        }
        System.out.println("⚠️ 비동기 DB 저장 대기 타임아웃 (30초 초과)");
    }

    private long getRedisStock(Long targetConcertId) {
        String value = redisTemplate.opsForValue().get("stock:concert:" + targetConcertId);
        return value == null ? 0 : Long.parseLong(value);
    }

    private long getQueueCount(String keyPrefix, Long targetConcertId) {
        Long count = redisTemplate.opsForZSet().zCard(keyPrefix + targetConcertId);
        return count == null ? 0 : count;
    }

    private void clearQueues(Long targetConcertId) {
        redisTemplate.delete(List.of(
                PROCESSING_KEY_PREFIX + targetConcertId,
                WAITING_KEY_PREFIX + targetConcertId
        ));
    }

    private void printConcurrencyResult(int success, int fail, long redisStock, int reservationCount) {
        System.out.println("\n=== WaitingRoom 동시 500명 테스트 결과 ===");
        System.out.printf("총 요청 수      : %d%n", THREAD_COUNT);
        System.out.printf("처리열 정원     : %d%n", PROCESSING_QUEUE_SIZE);
        System.out.printf("성공 수         : %d%n", success);
        System.out.printf("실패 수         : %d%n", fail);
        System.out.printf("Redis 최종 재고 : %d%n", redisStock);
        System.out.printf("DB 예약 건수    : %d%n", reservationCount);
        System.out.printf("오버부킹        : %s%n",
                reservationCount > INITIAL_STOCK
                        ? "발생 (" + (reservationCount - INITIAL_STOCK) + "건) ❌"
                        : "없음 ✅");
        System.out.println();
        System.out.println("[트레이드오프] 처리열 외 300명은 DB/Redis 접근 없이 즉시 차단 — 서버 보호 효과");
    }
}
