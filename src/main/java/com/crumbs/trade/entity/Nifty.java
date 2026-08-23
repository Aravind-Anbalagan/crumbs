package com.crumbs.trade.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "FO_STOCKS")
@Data
public class Nifty {

    @Id
    @Column(name="id", nullable = false, unique = true)
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private Long id;

    @Column(name="name")
    private String name;

    @Column(name="is_active")
    private Boolean isActive;

    @Column(name="token")
    private String token;

    // 🔥 NEW: To store yesterday's closing price
    @Column(name="prev_close", precision = 10, scale = 2)
    private BigDecimal prevClose;

    // 🔥 NEW: To ensure the scanner only uses today's fresh data
    @Column(name="prev_close_date")
    private LocalDate prevCloseDate;

    @Column(name = "percentage_change")
    private BigDecimal percentageChange;

    @Column(name = "percentage_updated_time")
    private LocalDateTime percentageUpdatedTime;

    // 🔥 NEW: Range-bucket tracking for interval alerts
    @Column(name = "current_range_bucket")
    private String currentRangeBucket;   // e.g. "5-6%", "9-10%", "10%+" or null

    @Column(name = "range_direction")
    private String rangeDirection;       // "UP" or "DOWN" or null

    @Column(name = "range_entered_at")
    private LocalDateTime rangeEnteredAt; // when it entered the CURRENT bucket+direction
}