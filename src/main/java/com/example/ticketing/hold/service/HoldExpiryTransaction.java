package com.example.ticketing.hold.service;

import com.example.ticketing.hold.domain.ClaimedHold;
import com.example.ticketing.hold.dto.HoldExpiry;
import com.example.ticketing.hold.repository.SeatHoldJdbcRepository;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.seat.repository.SeatJdbcRepository;
import com.example.ticketing.seat.repository.SeatRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 집기와 상태 변경을 한 트랜잭션에서 한다. 트랜잭션 밖의 SKIP LOCKED는 락이 바로 풀려 서버끼리 같은 행을 집는다
@Component
@RequiredArgsConstructor
public class HoldExpiryTransaction {

    private final SeatHoldJdbcRepository seatHoldJdbcRepository;
    private final SeatJdbcRepository seatJdbcRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<HoldExpiry> claimAndExpire(int batch) {
        List<ClaimedHold> claimed = seatHoldJdbcRepository.claimExpired(batch);
        if (claimed.isEmpty()) {
            return List.of();
        }
        List<Long> ids = claimed.stream().map(ClaimedHold::holdId).toList();
        Map<Long, List<Long>> seatsByHold = seatJdbcRepository.findHeldSeatIdsByHold(ids);

        seatHoldRepository.markExpired(ids);
        seatRepository.releaseHeldSeatsOf(ids);

        return claimed.stream()
                .map(h -> new HoldExpiry(h.holdId(), h.gate(seatsByHold.getOrDefault(h.holdId(), List.of()))))
                .toList();
    }
}
