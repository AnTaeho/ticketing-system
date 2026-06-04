package com.example.ticketing.reservation.service;

import com.example.ticketing.reservation.dto.ReserveResponse;

public interface TicketService {

    ReserveResponse reserve(Long concertId, Long userId);
}
