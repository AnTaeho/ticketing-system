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

    public static String code(SeatStatus status) {
        return switch (status) {
            case AVAILABLE -> "A";
            case HELD -> "H";
            case SOLD -> "S";
        };
    }
}
