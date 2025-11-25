package com.crumbs.trade.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(name = "Indexes")
public class Indexes {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, unique = true)
    Long id;

    @Column(name = "name")
    String name;

    @Column(name = "token")
    String token;

    @Column(name = "exchange")
    String exchange;

    @Column(name = "fromDate")
    String fromDate;

    @Column(name = "toDate")
    String toDate;

    @Column(name = "timeFrame")
    String timeFrame;

    @Column(name = "volume")
    String volume;

    @Column(name = "symbol")
    String symbol;

    @Column(name = "active")
    String active;

    @Column(name = "strike")
    String strike;

    @Column(name = "lotsize")
    int lotsize;

    @Column(name = "expiry")
    String expiry;

    // ⭐ NEW FIELD
    @Column(name = "created_date")
    String createdDate;

    // Auto-populate createdDate before inserting
    @PrePersist
    public void onCreate() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss");
        this.createdDate = LocalDateTime.now().format(formatter);
    }
}
