package com.example.ticketing.reservation.controller;

import com.example.ticketing.reservation.dto.ReserveRequest;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.service.TicketServiceV3;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3")
@RequiredArgsConstructor
public class ReserveControllerV3 {

    private final TicketServiceV3 ticketService;

    @PostMapping("/concerts/{concertId}/reserve")
    public ResponseEntity<ReserveResponse> reserve(
            @PathVariable Long concertId,
            @RequestBody @Valid ReserveRequest request) {
        return ResponseEntity.ok(ticketService.reserve(concertId, request.userId()));
    }
}
