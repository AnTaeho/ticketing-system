package com.example.ticketing.reservation.controller;

import com.example.ticketing.reservation.dto.ReserveRequest;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.service.v6.TicketServiceV6;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v6")
@RequiredArgsConstructor
public class ReserveControllerV6 {

    private final TicketServiceV6 ticketService;

    @PostMapping("/concerts/{concertId}/reserve")
    public ResponseEntity<ReserveResponse> reserve(
            @PathVariable Long concertId,
            @RequestBody @Valid ReserveRequest request) {
        return ResponseEntity.ok(ticketService.reserve(concertId, request.userId()));
    }

    @GetMapping("/reservations/{ticketToken}/status")
    public ResponseEntity<ReserveResponse> getStatus(@PathVariable String ticketToken) {
        return ResponseEntity.ok(ticketService.getReservationStatus(ticketToken));
    }
}
