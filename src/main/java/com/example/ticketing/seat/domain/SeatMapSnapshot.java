package com.example.ticketing.seat.domain;

import java.util.List;

// 좌석맵 응답 형식. 화면이 매초 받으므로 좌석 상태는 한 글자(s: A/H/S)로 줄인다
public record SeatMapSnapshot(long performanceId, long generatedAt, List<Section> sections) {

    public record Section(String section, List<Row> rows) {
    }

    public record Row(int rowNo, List<SeatCell> seats) {
    }

    public record SeatCell(long id, int no, String grade, int price, String s) {
    }

    // HELD가 남아 있으면 결제 실패나 만료로 다시 풀릴 수 있으므로 매진이 아니다
    public boolean everySeatSold() {
        String sold = code(SeatStatus.SOLD);
        return sections.stream()
                .flatMap(section -> section.rows().stream())
                .flatMap(row -> row.seats().stream())
                .allMatch(seat -> sold.equals(seat.s()));
    }

    public static String code(SeatStatus status) {
        return switch (status) {
            case AVAILABLE -> "A";
            case HELD -> "H";
            case SOLD -> "S";
        };
    }
}
