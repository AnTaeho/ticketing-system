package com.example.ticketing.booking.show;

import com.example.ticketing.booking.show.domain.Show;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowRepository extends JpaRepository<Show, Long> {

    boolean existsByTitle(String title);
}
