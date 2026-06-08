package com.example.ticketing.reservation.controller;

import com.example.ticketing.reservation.dto.ReserveRequestV7;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.service.v7.TicketServiceV7;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v7/concerts")
@RequiredArgsConstructor
public class ReserveControllerV7 {

    private final TicketServiceV7 ticketServiceV7;

    @PostMapping("/{concertId}/reserve")
    public ResponseEntity<ReserveResponse> reserve(
            @PathVariable Long concertId,
            @Valid @RequestBody ReserveRequestV7 request) {
        return ResponseEntity.ok(
                ticketServiceV7.reserve(concertId, request.userId(), request.queueToken())
        );
    }
}
