package com.example.ticketing.reservation.service.v3;

import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceV3 implements TicketService {

    private static final int MAX_RETRY = 10;

    private final OptimisticLockRetryer retryer;
    private final TicketTransactionV3 transaction;

    @Override
    public ReserveResponse reserve(Long concertId, Long userId) {
        return retryer.executeWithRetry(
                () -> transaction.reserveInTransaction(concertId, userId),
                concertId,
                MAX_RETRY
        );
    }
}
