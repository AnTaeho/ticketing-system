package com.example.ticketing.controller;

import com.example.ticketing.controller.dto.ReserveRequest;
import com.example.ticketing.controller.dto.ReserveResponse;
import com.example.ticketing.service.TicketServiceV1;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReserveControllerV1 {

    private final TicketServiceV1 ticketService;

    @PostMapping("/concerts/{concertId}/reserve")
    public ResponseEntity<ReserveResponse> reserve(
            @PathVariable Long concertId,
            @RequestBody @Valid ReserveRequest request) {
        return ResponseEntity.ok(ticketService.reserve(concertId, request.userId()));
    }
}
