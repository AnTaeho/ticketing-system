package com.example.ticketing.waitingroom.controller;

import com.example.ticketing.waitingroom.dto.ReserveRequest;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.waitingroom.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/waitingroom/concerts")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping("/{concertId}/reserve")
    public ResponseEntity<ReserveResponse> reserve(
            @PathVariable Long concertId,
            @Valid @RequestBody ReserveRequest request) {
        return ResponseEntity.ok(
                reservationService.reserve(concertId, request.userId(), request.queueToken())
        );
    }
}
