package com.example.ticketing.show.repository;

import com.example.ticketing.show.domain.Show;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowRepository extends JpaRepository<Show, Long> {

    boolean existsByTitle(String title);
}
