package com.example.ticketing.service;

import com.example.ticketing.domain.Concert;
import com.example.ticketing.exception.ConcertNotFoundException;
import com.example.ticketing.repository.ConcertRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConcertService {

    private final ConcertRepository concertRepository;

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
    }

    private Concert findConcertById(Long concertId) {
        return concertRepository.findById(concertId)
                .orElseThrow(() -> new ConcertNotFoundException(concertId));
    }
}
