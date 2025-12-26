package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "FUTURES_CONFIG")
@Data
public class FuturesConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_date")
    private LocalDate executionDate;  // ✅ this replaces fromDate & toDate

    @Column(name = "movement_percent", nullable = false)
    private BigDecimal movementPercent;

    @Column(name = "profit_percent", nullable = false)
    private BigDecimal profitPercent;

    @Column(name = "loss_percent", nullable = false)
    private BigDecimal lossPercent;

    @Column(name = "use_nifty_expiry")
    private String useNiftyExpiry;

    @Column(name = "active")
    private String active;
    
    @Column(name = "notificationRequired")
    private String notificationRequired;
}
