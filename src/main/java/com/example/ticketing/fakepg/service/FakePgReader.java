package com.example.ticketing.fakepg.service;

import com.example.ticketing.fakepg.domain.FakePgOrder;
import com.example.ticketing.fakepg.repository.FakePgOrderRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 부르는 쪽 트랜잭션과 떼어 매번 커밋된 최신 행을 읽는다
@Component
@RequiredArgsConstructor
public class FakePgReader {

    private final FakePgOrderRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public Optional<FakePgOrder> find(String orderId) {
        return repository.findById(orderId);
    }
}
