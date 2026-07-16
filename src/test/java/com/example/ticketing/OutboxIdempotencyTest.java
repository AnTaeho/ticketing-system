package com.example.ticketing;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.reservation.kafka.ReservationMessage;
import com.example.ticketing.reservation.kafka.TicketConsumer;
import com.example.ticketing.reservation.repository.ReservationRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OutboxIdempotencyTest {

    @Autowired private TicketConsumer        ticketConsumer;
    @Autowired private ConcertRepository     concertRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ObjectMapper          objectMapper;

    private static final Acknowledgment NO_OP_ACK = () -> {};

    private Long concertId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        concertRepository.deleteAll();
        Concert concert = concertRepository.save(Concert.create("멱등성 테스트 공연", 100));
        concertId = concert.getId();
    }

    @Test
    @DisplayName("동일 ticketToken 메시지 2회 처리 → 예약 1건만 생성 (멱등성 보장)")
    void 동일_메시지_중복_처리시_예약_1건만_생성() throws Exception {
        String ticketToken = UUID.randomUUID().toString();
        String payload = objectMapper.writeValueAsString(
                new ReservationMessage(concertId, 1L, ticketToken)
        );

        ticketConsumer.consumeReservationRequest(payload, NO_OP_ACK);
        ticketConsumer.consumeReservationRequest(payload, NO_OP_ACK);

        int reservationCount = reservationRepository.countByConcertId(concertId);
        assertThat(reservationCount)
                .as("중복 처리 시 예약이 2건 생성됨 — 멱등성 실패")
                .isEqualTo(1);

        System.out.println("\n=== 멱등성 검증 결과 ===");
        System.out.println("동일 메시지 처리 횟수 : 2회");
        System.out.println("생성된 예약 건수      : " + reservationCount + " ✅");
        System.out.println("ticketToken          : " + ticketToken);
    }

    @Test
    @DisplayName("서로 다른 ticketToken 메시지 → 각각 예약 생성 (정상 처리 확인)")
    void 다른_ticketToken_메시지는_각각_예약_생성() throws Exception {
        String token1 = UUID.randomUUID().toString();
        String token2 = UUID.randomUUID().toString();

        ticketConsumer.consumeReservationRequest(
                objectMapper.writeValueAsString(new ReservationMessage(concertId, 1L, token1)), NO_OP_ACK
        );
        ticketConsumer.consumeReservationRequest(
                objectMapper.writeValueAsString(new ReservationMessage(concertId, 2L, token2)), NO_OP_ACK
        );

        int reservationCount = reservationRepository.countByConcertId(concertId);
        assertThat(reservationCount).isEqualTo(2);

        System.out.println("\n=== 정상 다중 처리 결과 ===");
        System.out.println("생성된 예약 건수: " + reservationCount + " ✅");
    }
}
