package com.example.ticketing.booking.purchase;

import com.example.ticketing.booking.purchase.domain.Booking;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByHoldId(Long holdId);
}
