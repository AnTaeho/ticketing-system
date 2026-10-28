package com.example.ticketing.fakepg.service;

import com.example.ticketing.fakepg.repository.FakePgOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 쓰기마다 짧은 트랜잭션을 따로 커밋한다. 승인 지연을 기다리는 동안 PROCESSING이 다른 요청에 보여야 한다
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class FakePgTransaction {

    private final FakePgOrderRepository repository;

    public int insertProcessing(String orderId, int amount) {
        return repository.insertProcessing(orderId, amount);
    }

    public int insertCancelled(String orderId) {
        return repository.insertCancelled(orderId);
    }

    public int processingToApproved(String orderId, String pgTxId) {
        return repository.approveProcessing(orderId, pgTxId);
    }

    public int processingToDeclined(String orderId) {
        return repository.declineProcessing(orderId);
    }

    public int staleProcessingToDeclined(String orderId, long staleMicros) {
        return repository.declineStaleProcessing(orderId, staleMicros);
    }

    public int approvedToCancelled(String orderId) {
        return repository.cancelApproved(orderId);
    }
}
