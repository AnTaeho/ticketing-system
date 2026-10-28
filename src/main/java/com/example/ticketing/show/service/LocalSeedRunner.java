package com.example.ticketing.show.service;

import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.show.dto.NewPerformance;
import com.example.ticketing.show.repository.ShowRepository;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 로컬에서 띄우자마자 화면 시연을 할 수 있게 예매 중 회차와 곧 열릴 회차를 하나씩 만든다
@Slf4j
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "booking.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class LocalSeedRunner implements ApplicationRunner {

    public static final String SEED_TITLE = "별빛 정거장 겨울 콘서트";
    static final String SEED_VENUE = "새벽숲 아트홀";
    static final String SEED_DESCRIPTION = "네 명의 연주자가 들려주는 겨울 밤 어쿠스틱 무대예요. 공연 시간은 약 120분이에요.";
    static final int SECTIONS = 4;
    static final int ROWS = 10;
    static final int SEATS_PER_ROW = 20;
    private static final LocalTime SHOW_TIME = LocalTime.of(19, 30);

    private final ShowAdminService showAdminService;
    private final ShowRepository showRepository;
    private final DbClock dbClock;

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    public void seed() {
        if (showRepository.existsByTitle(SEED_TITLE)) {
            log.info("[booking seed] 시드 공연이 이미 있어 건너뛴다: {}", SEED_TITLE);
            return;
        }
        LocalDateTime now = dbClock.now().withNano(0);
        Long showId = showAdminService.createShow(SEED_TITLE, SEED_VENUE, SEED_DESCRIPTION);
        long openNow = createPerformance(showId, now.minusMinutes(1), now.toLocalDate().plusDays(3).atTime(SHOW_TIME));
        long openSoon = createPerformance(showId, now.plusMinutes(10), now.toLocalDate().plusDays(4).atTime(SHOW_TIME));
        log.info("[booking seed] 시드 공연 생성: showId={}, 예매 중 회차={}, 10분 뒤 열리는 회차={}",
                showId, openNow, openSoon);
    }

    private long createPerformance(Long showId, LocalDateTime openAt, LocalDateTime startsAt) {
        return showAdminService.createPerformance(
                new NewPerformance(showId, startsAt, openAt, null, SECTIONS, ROWS, SEATS_PER_ROW)).performanceId();
    }
}
