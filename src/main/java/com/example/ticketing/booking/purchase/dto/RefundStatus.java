package com.example.ticketing.booking.purchase.dto;

import com.example.ticketing.booking.payment.domain.BookingPaymentStatus;
import com.example.ticketing.booking.purchase.domain.BookingStatus;

// 취소한 예매의 환불 진행. 취소하지 않은 예매는 null
public enum RefundStatus {
    REQUESTED,
    DONE;

    public static RefundStatus of(BookingStatus bookingStatus, BookingPaymentStatus paymentStatus) {
        if (bookingStatus != BookingStatus.CANCELLED) {
            return null;
        }
        return paymentStatus == BookingPaymentStatus.CANCELLED ? DONE : REQUESTED;
    }
}
