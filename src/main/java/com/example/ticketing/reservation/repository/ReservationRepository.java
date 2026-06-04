package com.example.ticketing.reservation.repository;

import com.example.ticketing.reservation.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    int countByConcertId(Long concertId);
}
