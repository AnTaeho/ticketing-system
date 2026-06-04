package com.example.ticketing.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Concert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private int totalStock;
    private int stock;

    @Version
    private Long version;

    public static Concert create(String title, int stock) {
        Concert concert = new Concert();
        concert.title = title;
        concert.totalStock = stock;
        concert.stock = stock;
        return concert;
    }

    public void decrease() {
        if (isOutOfStock()) {
            throw new IllegalStateException("재고 없음");
        }
        this.stock--;
    }

    public boolean isOutOfStock() {
        return this.stock <= 0;
    }

    public void resetStock(int stock) {
        this.stock = stock;
        this.totalStock = stock;
    }
}
