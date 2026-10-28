package com.example.ticketing.notification.repository;

import com.example.ticketing.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByBookingId(Long bookingId);
}
