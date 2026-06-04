package com.example.ticketing.repository;

import com.example.ticketing.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    int countByConcertId(Long concertId);
}
