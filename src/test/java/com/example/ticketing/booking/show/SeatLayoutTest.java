package com.example.ticketing.booking.show;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.booking.show.SeatLayout.SeatSpec;
import com.example.ticketing.booking.show.domain.SeatGrade;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SeatLayoutTest {

    @Test
    @DisplayName("4구역 × 10열 × 20석은 800석이고 위치가 겹치지 않는다")
    void defaultLayoutHas800Seats() {
        List<SeatSpec> specs = SeatLayout.generate(4, 10, 20);

        assertThat(specs).hasSize(800);
        assertThat(specs.stream().map(s -> s.section() + "-" + s.rowNo() + "-" + s.seatNo()).distinct())
                .hasSize(800);
    }

    @Test
    @DisplayName("구역 이름은 A부터 차례로 붙고 구역마다 200석이다")
    void sectionNames() {
        Map<String, Long> perSection = SeatLayout.generate(4, 10, 20).stream()
                .collect(Collectors.groupingBy(SeatSpec::section, Collectors.counting()));

        assertThat(perSection).containsOnlyKeys("A", "B", "C", "D");
        assertThat(perSection.values()).containsOnly(200L);
    }

    @ParameterizedTest(name = "{0}열 → {1} {2}원")
    @CsvSource({
            "1, VIP, 180000", "2, VIP, 180000",
            "3, R, 150000", "4, R, 150000", "5, R, 150000",
            "6, S, 120000", "7, S, 120000", "8, S, 120000",
            "9, A, 90000", "10, A, 90000"
    })
    @DisplayName("열별 등급과 가격")
    void gradeAndPriceByRow(int rowNo, SeatGrade grade, int price) {
        List<SeatSpec> row = SeatLayout.generate(4, 10, 20).stream()
                .filter(s -> s.rowNo() == rowNo)
                .toList();

        assertThat(row).hasSize(80);
        assertThat(row).allSatisfy(s -> {
            assertThat(s.grade()).isEqualTo(grade);
            assertThat(s.price()).isEqualTo(price);
        });
    }

    @Test
    @DisplayName("등급별 좌석 수: VIP 160, R 240, S 240, A 160")
    void countByGrade() {
        Map<SeatGrade, Long> byGrade = SeatLayout.generate(4, 10, 20).stream()
                .collect(Collectors.groupingBy(SeatSpec::grade, Collectors.counting()));

        assertThat(byGrade).containsExactlyInAnyOrderEntriesOf(Map.of(
                SeatGrade.VIP, 160L, SeatGrade.R, 240L, SeatGrade.S, 240L, SeatGrade.A, 160L));
    }

    @Test
    @DisplayName("10열을 넘는 열도 A석이다")
    void rowsBeyondTenAreGradeA() {
        Map<Integer, SeatGrade> gradeByRow = SeatLayout.generate(1, 12, 1).stream()
                .collect(Collectors.toMap(SeatSpec::rowNo, SeatSpec::grade));

        assertThat(gradeByRow.get(11)).isEqualTo(SeatGrade.A);
        assertThat(gradeByRow.get(12)).isEqualTo(SeatGrade.A);
    }

    @Test
    @DisplayName("좌석 번호는 열마다 1부터 시작한다")
    void seatNumbersStartAtOne() {
        Map<Integer, SeatSpec> firstRowOfA = SeatLayout.generate(1, 1, 5).stream()
                .collect(Collectors.toMap(SeatSpec::seatNo, Function.identity()));

        assertThat(firstRowOfA).containsOnlyKeys(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("구역·열·좌석 수가 범위를 벗어나면 거절한다")
    void rejectsInvalidSize() {
        assertThatThrownBy(() -> SeatLayout.generate(0, 10, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeatLayout.generate(27, 10, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeatLayout.generate(4, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeatLayout.generate(4, 10, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
