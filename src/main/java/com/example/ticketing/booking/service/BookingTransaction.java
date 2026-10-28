package com.example.ticketing.booking.service;

import com.example.ticketing.booking.domain.Booking;
import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.booking.dto.RefundStatus;
import com.example.ticketing.booking.repository.BookingRepository;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.jdbc.RowCounts;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.service.OutboxWriter;
import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.repository.PaymentRepository;
import com.example.ticketing.seat.repository.SeatRepository;
import com.example.ticketing.show.repository.PerformanceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// 락 순서: booking → seat(id 오름차순). 확정된 hold를 바꾸는 경로가 없어 hold 행은 잠그지 않는다. 이 안에서는 Redis·PG를 부르지 않는다
@Component
@RequiredArgsConstructor
public class BookingTransaction {

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final PerformanceRepository performanceRepository;
    private final OutboxWriter outboxWriter;
    private final JsonMapper jsonMapper;

    // booking 락이 같은 예매의 취소를 줄 세운다. 뒤에 온 쪽은 CANCELLED를 보고 아무것도 바꾸지 않으므로 환불 작업은 하나다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Cancellation cancel(long userId, long bookingId) {
        Booking booking = bookingRepository.lockByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND,
                        Map.of("bookingId", bookingId)));
        Payment payment = paymentRepository.findById(booking.getPaymentId())
                .orElseThrow(() -> new IllegalStateException("예매 " + bookingId + "의 결제가 없다"));
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return Cancellation.unchanged(booking, RefundStatus.of(BookingStatus.CANCELLED, payment.getStatus()));
        }
        if (performanceRepository.countStarted(booking.getPerformanceId()) > 0) {
            throw new ApiException(ErrorCode.CANCEL_NOT_ALLOWED, Map.of("bookingId", bookingId));
        }

        RowCounts.requireExactly(bookingRepository.markCancelled(bookingId), 1, "booking CANCELLED");
        SeatHold hold = seatHoldRepository.findById(booking.getHoldId())
                .orElseThrow(() -> new IllegalStateException("예매 " + bookingId + "의 hold가 없다"));
        // 게이트 키를 지울 좌석 ID는 hold_id를 비우기 전에 읽어 둔다
        List<Long> seatIds = seatRepository.lockSoldSeatIds(hold.getId());
        RowCounts.requireExactly(seatRepository.releaseSoldSeats(hold.getId()), seatIds.size(), "seat AVAILABLE");
        outboxWriter.write(OutboxTaskType.REFUND_REQUESTED, payment.getId(),
                json("paymentId", payment.getId(), "orderId", payment.getOrderId()));
        return new Cancellation(bookingId, booking.getPerformanceId(), true, hold.getHoldToken(),
                List.copyOf(seatIds), RefundStatus.REQUESTED);
    }

    private String json(Object... keyValues) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            payload.put((String) keyValues[i], keyValues[i + 1]);
        }
        return jsonMapper.writeValueAsString(payload);
    }

    // applied가 true일 때만 게이트에 반영할 좌석과 토큰이 있다
    public record Cancellation(long bookingId, long performanceId, boolean applied, String holdToken,
                               List<Long> seatIds, RefundStatus refund) {

        static Cancellation unchanged(Booking booking, RefundStatus refund) {
            return new Cancellation(booking.getId(), booking.getPerformanceId(), false, null, List.of(), refund);
        }
    }
}
