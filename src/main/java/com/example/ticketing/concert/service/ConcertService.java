package com.example.ticketing.concert.service;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.global.exception.ConcertNotFoundException;
import com.example.ticketing.global.stock.RedisStockRepository;
import com.example.ticketing.concert.repository.ConcertRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class ConcertService {

    private final ConcertRepository concertRepository;
    private final RedisStockRepository redisStockRepository;

    @Transactional(readOnly = true)
    public List<Concert> getConcerts(int page, int size) {
        return concertRepository.findAll(PageRequest.of(page, size)).getContent();
    }

    @Transactional(readOnly = true)
    public Concert getConcert(Long concertId) {
        return findConcertById(concertId);
    }

    @Transactional(readOnly = true)
    public int getStock(Long concertId) {
        return findConcertById(concertId).getStock();
    }

    @Transactional
    public void resetStock(Long concertId, int stock) {
        Concert concert = findConcertById(concertId);
        concert.resetStock(stock);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisStockRepository.initStock(concertId, stock);
            }
        });
    }

    @Transactional
    public Concert createConcert(String title, int stock) {
        Concert concert = concertRepository.save(Concert.create(title, stock));
        Long concertId = concert.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisStockRepository.initStock(concertId, stock);
            }
        });
        return concert;
    }

    private Concert findConcertById(Long concertId) {
        return concertRepository.findById(concertId)
                .orElseThrow(() -> new ConcertNotFoundException(concertId));
    }
}
