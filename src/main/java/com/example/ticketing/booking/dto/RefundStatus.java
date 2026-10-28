package com.example.ticketing.booking.dto;

import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.payment.domain.PaymentStatus;

// 취소한 예매의 환불 진행. 취소하지 않은 예매는 null
public enum RefundStatus {
    REQUESTED,
    DONE;

    public static RefundStatus of(BookingStatus bookingStatus, PaymentStatus paymentStatus) {
        if (bookingStatus != BookingStatus.CANCELLED) {
            return null;
        }
        return paymentStatus == PaymentStatus.CANCELLED ? DONE : REQUESTED;
    }
}
