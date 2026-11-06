package com.example.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.repository.BookingRepository;
import com.example.ticketing.booking.repository.BookingSeatRepository;
import com.example.ticketing.fakepg.repository.FakePgOrderRepository;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.payment.domain.PaymentStatus;
import com.example.ticketing.payment.repository.PaymentRepository;
import com.example.ticketing.seat.repository.SeatRepository;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

// JPQL은 enum 이름이 있는지만 기동 때 검사하므로, 상태 목록이 enum 규칙과 같은지와 조건부 UPDATE 옵션은 여기서 검사한다
class RepositoryQueryRulesTest {

    private static final Pattern IN_LIST = Pattern.compile("status\\s+(?i:in)\\s*\\(([^)]*)\\)");
    private static final Pattern ENUM_NAME = Pattern.compile("[A-Z][A-Z_]+");

    @Test
    @DisplayName("결제 상태 IN 목록은 모두 PaymentStatus.isOpen()인 상태와 같다")
    void openPaymentStatusListsMatchEnum() {
        List<String> open = Arrays.stream(PaymentStatus.values()).filter(PaymentStatus::isOpen).map(Enum::name)
                .toList();
        List<List<String>> inLists = new ArrayList<>();
        for (String query : queriesOf(PaymentRepository.class)) {
            Matcher clause = IN_LIST.matcher(query);
            while (clause.find()) {
                List<String> names = new ArrayList<>();
                Matcher name = ENUM_NAME.matcher(clause.group(1));
                while (name.find()) {
                    names.add(name.group());
                }
                inLists.add(names);
            }
        }

        assertThat(inLists).isNotEmpty()
                .allSatisfy(list -> assertThat(list).containsExactlyInAnyOrderElementsOf(open));
    }

    @Test
    @DisplayName("저장소의 조건부 UPDATE는 모두 flush·clear 옵션을 켠다")
    void modifyingOptionsAreConsistent() {
        List<Method> modifying = new ArrayList<>();
        for (Class<?> repository : List.of(SeatHoldRepository.class, SeatRepository.class, PaymentRepository.class,
                BookingRepository.class, BookingSeatRepository.class,
                FakePgOrderRepository.class)) {
            Arrays.stream(repository.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Modifying.class))
                    .forEach(modifying::add);
        }

        assertThat(modifying).isNotEmpty().allSatisfy(method -> {
            Modifying options = method.getAnnotation(Modifying.class);
            assertThat(options.clearAutomatically()).as(method.getName()).isTrue();
            assertThat(options.flushAutomatically()).as(method.getName()).isTrue();
        });
    }

    private static List<String> queriesOf(Class<?> repository) {
        List<String> queries = new ArrayList<>();
        for (Method method : repository.getDeclaredMethods()) {
            Query query = method.getAnnotation(Query.class);
            if (query != null) {
                queries.add(query.value());
            }
        }
        assertThat(queries).as(repository.getSimpleName() + " 쿼리").isNotEmpty();
        return queries;
    }
}
