package com.example.ticketing.show.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// show는 MySQL 예약어라 테이블 이름을 show_info로 둔다
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "show_info")
public class Show {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 100)
    private String venueName;

    @Column(length = 1000)
    private String description;

    public static Show create(String title, String venueName, String description) {
        Show show = new Show();
        show.title = title;
        show.venueName = venueName;
        show.description = description;
        return show;
    }
}
