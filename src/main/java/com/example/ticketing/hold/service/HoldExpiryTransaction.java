package com.example.ticketing.hold.service;

import com.example.ticketing.common.jdbc.RowCounts;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.hold.dto.HoldExpiry;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.seat.domain.Seat;
import com.example.ticketing.seat.domain.SeatStatus;
import com.example.ticketing.seat.repository.SeatRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 집기와 상태 변경을 한 트랜잭션에서 한다. 트랜잭션 밖의 SKIP LOCKED는 락이 바로 풀려 의미가 없다
@Component
@Transactional(isolation = Isolation.READ_COMMITTED)
@RequiredArgsConstructor
public class HoldExpiryTransaction {

    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;

    public List<HoldExpiry> claimAndExpire(int batch) {
        List<SeatHold> claimed = seatHoldRepository.claimExpired(Limit.of(batch));
        if (claimed.isEmpty()) {
            return List.of();
        }
        List<Long> ids = claimed.stream().map(SeatHold::getId).toList();
        Map<Long, List<Long>> seatsByHold = seatRepository.findByHoldIdInAndStatusOrderById(ids, SeatStatus.HELD)
                .stream()
                .collect(Collectors.groupingBy(Seat::getHoldId,
                        Collectors.mapping(Seat::getId, Collectors.toList())));

        RowCounts.requireExactly(seatHoldRepository.heldToExpired(ids), ids.size(), "hold EXPIRED");
        // 좌석은 잠그지 않으므로 hold_id 조건부 UPDATE로 푼다
        seatRepository.heldToAvailableOfHolds(ids);

        return claimed.stream()
                .map(h -> new HoldExpiry(h.getId(), new GateHold(h.getPerformanceId(), h.getHoldToken(),
                        seatsByHold.getOrDefault(h.getId(), List.of()))))
                .toList();
    }
}
