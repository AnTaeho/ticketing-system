package com.example.ticketing.fakepg.service;

import com.example.ticketing.fakepg.domain.FakePgStatus;
import com.example.ticketing.fakepg.repository.FakePgOrderRepository;
import java.time.Duration;
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

    // 같은 주문이 이미 있으면 DataIntegrityViolationException이다
    public void insertProcessing(String orderId, int amount) {
        repository.insert(orderId, amount, FakePgStatus.PROCESSING);
    }

    // 승인 전에 도착한 취소가 남기는 묘비. 이후 승인 요청은 거절된다
    public void insertCancelled(String orderId) {
        repository.insert(orderId, 0, FakePgStatus.CANCELLED);
    }

    public int processingToApproved(String orderId, String pgTxId) {
        return repository.processingToApproved(orderId, pgTxId);
    }

    public int processingToDeclined(String orderId) {
        return repository.processingToDeclined(orderId);
    }

    public int staleProcessingToDeclined(String orderId, Duration staleAfter) {
        return repository.staleProcessingToDeclined(orderId, staleAfter.toNanos());
    }

    public int approvedToCancelled(String orderId) {
        return repository.approvedToCancelled(orderId);
    }
}
