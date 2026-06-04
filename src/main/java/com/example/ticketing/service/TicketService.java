package com.example.ticketing.service;

import com.example.ticketing.controller.dto.ReserveResponse;

public interface TicketService {

    ReserveResponse reserve(Long concertId, Long userId);
}
