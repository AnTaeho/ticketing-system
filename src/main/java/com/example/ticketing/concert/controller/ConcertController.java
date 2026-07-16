package com.example.ticketing.concert.controller;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.dto.ConcertCreateRequest;
import com.example.ticketing.concert.service.ConcertService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/concerts")
@RequiredArgsConstructor
public class ConcertController {

    private final ConcertService concertService;

    @PostMapping
    public ResponseEntity<Concert> createConcert(@Valid @RequestBody ConcertCreateRequest request) {
        Concert concert = concertService.createConcert(request.title(), request.stock());
        return ResponseEntity.status(HttpStatus.CREATED).body(concert);
    }

    @GetMapping
    public ResponseEntity<List<Concert>> getConcerts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(concertService.getConcerts(page, size));
    }

    @GetMapping("/{concertId}")
    public ResponseEntity<Concert> getConcert(@PathVariable Long concertId) {
        return ResponseEntity.ok(concertService.getConcert(concertId));
    }

    @GetMapping("/{concertId}/stock")
    public ResponseEntity<Map<String, Integer>> getStock(@PathVariable Long concertId) {
        return ResponseEntity.ok(Map.of("stock", concertService.getStock(concertId)));
    }
}
