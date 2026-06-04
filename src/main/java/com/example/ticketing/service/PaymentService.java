package com.example.ticketing.service;

import com.example.ticketing.domain.Payment;
import com.example.ticketing.domain.PaymentStatus;
import com.example.ticketing.domain.Reservation;
import com.example.ticketing.domain.ReservationStatus;
import com.example.ticketing.exception.ConcertNotFoundException;
import com.example.ticketing.repository.PaymentRepository;
import com.example.ticketing.repository.ReservationRepository;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final long PAYMENT_MIN_DELAY_MS = 100;
    private static final long PAYMENT_MAX_DELAY_MS = 200;

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;

    @Transactional
    public Payment pay(Long reservationId) {
        Reservation reservation = findReservationById(reservationId);
        simulateExternalPgDelay();

        reservation.updateStatus(ReservationStatus.SUCCESS);
        Payment payment = paymentRepository.save(Payment.of(reservationId, PaymentStatus.PAID));

        log.info("[결제] 결제 완료 - reservationId={}, paymentId={}", reservationId, payment.getId());
        return payment;
    }

    private Reservation findReservationById(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ConcertNotFoundException(reservationId));
    }

    private void simulateExternalPgDelay() {
        try {
            long delay = ThreadLocalRandom.current().nextLong(PAYMENT_MIN_DELAY_MS, PAYMENT_MAX_DELAY_MS + 1);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
