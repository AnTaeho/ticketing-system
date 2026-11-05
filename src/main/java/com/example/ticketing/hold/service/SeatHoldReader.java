package com.example.ticketing.hold.service;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.hold.dto.ActiveHoldResult;
import com.example.ticketing.hold.dto.HoldDetailResult;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.seat.service.SeatCatalog;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeatHoldReader {

    private final SeatHoldRepository seatHoldRepository;
    private final SeatCatalog seatCatalog;
    private final DbClock dbClock;

    public HoldDetailResult getHold(long userId, long holdId) {
        SeatHold hold = seatHoldRepository.findByIdAndUserId(holdId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.HOLD_NOT_FOUND, Map.of("holdId", holdId)));
        return HoldDetailResult.from(hold,
                seatCatalog.seatsOfHold(hold.getPerformanceId(), holdId),
                seatHoldRepository.findPaymentIdByHoldId(holdId).orElse(null), dbClock.now());
    }

    // 이미 잡아 둔 자리가 있다는 응답을 받은 화면이 결제 화면으로 이어 가려고 쓴다
    public ActiveHoldResult getActiveHold(long userId, long performanceId) {
        return seatHoldRepository.findActive(userId, performanceId)
                .orElseThrow(() -> new ApiException(ErrorCode.HOLD_NOT_FOUND,
                        Map.of("performanceId", performanceId)));
    }
}
