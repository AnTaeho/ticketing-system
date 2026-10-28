package com.example.ticketing.notification.domain;

import com.example.ticketing.common.text.Texts;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

// 좌석은 구역·열·번 순으로 받아 첫 좌석만 적고 나머지는 「외 N석」으로 줄인다
public final class TicketIssuedMessage {

    private static final DateTimeFormatter STARTS_AT = DateTimeFormatter.ofPattern("yyyy.M.d(E) HH:mm", Locale.KOREAN);
    private static final int MAX_TITLE = 100;

    private TicketIssuedMessage() {
    }

    public static String of(IssuedBookingRow booking, List<IssuedSeatRow> seats) {
        IssuedSeatRow first = seats.get(0);
        String others = seats.size() > 1 ? " 외 " + (seats.size() - 1) + "석" : "";
        return "「%s」 %s %s구역 %d열 %d번%s 예매가 확정됐어요. 예매번호 %s".formatted(
                Texts.abbreviate(booking.title(), MAX_TITLE), booking.startsAt().format(STARTS_AT), first.section(),
                first.rowNo(), first.seatNo(), others, booking.bookingNumber());
    }
}
