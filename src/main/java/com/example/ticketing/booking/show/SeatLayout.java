package com.example.ticketing.booking.show;

import com.example.ticketing.booking.show.domain.SeatGrade;
import java.util.ArrayList;
import java.util.List;

public final class SeatLayout {

    private static final int MAX_SECTIONS = 26;

    private SeatLayout() {
    }

    public static List<SeatSpec> generate(int sections, int rows, int seatsPerRow) {
        if (sections < 1 || sections > MAX_SECTIONS || rows < 1 || seatsPerRow < 1) {
            throw new IllegalArgumentException(
                    "sections=%d rows=%d seatsPerRow=%d".formatted(sections, rows, seatsPerRow));
        }
        List<SeatSpec> specs = new ArrayList<>(sections * rows * seatsPerRow);
        for (int s = 0; s < sections; s++) {
            String section = sectionName(s);
            for (int rowNo = 1; rowNo <= rows; rowNo++) {
                SeatGrade grade = gradeOf(rowNo);
                for (int seatNo = 1; seatNo <= seatsPerRow; seatNo++) {
                    specs.add(new SeatSpec(section, rowNo, seatNo, grade, grade.getPrice()));
                }
            }
        }
        return specs;
    }

    public static SeatGrade gradeOf(int rowNo) {
        if (rowNo < 1) {
            throw new IllegalArgumentException("rowNo=" + rowNo);
        }
        if (rowNo <= 2) {
            return SeatGrade.VIP;
        }
        if (rowNo <= 5) {
            return SeatGrade.R;
        }
        if (rowNo <= 8) {
            return SeatGrade.S;
        }
        return SeatGrade.A;
    }

    private static String sectionName(int index) {
        return String.valueOf((char) ('A' + index));
    }

    public record SeatSpec(String section, int rowNo, int seatNo, SeatGrade grade, int price) {
    }
}
