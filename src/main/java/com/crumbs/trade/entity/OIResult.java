package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "oi_result")
@Data
public class OIResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Instrument (NIFTY / CRUDEOIL)
    private String name;

    // Strike
    private BigDecimal strike;

    // ==================== OI (POSITIONING) ====================
    private BigDecimal ceOi;   // 🔥 total CE OI (bar height)
    private BigDecimal peOi;   // 🔥 total PE OI

    // ==================== OI CHANGE (ACTIVITY) ====================
    private BigDecimal ceOiChange; // 🔥 intensity
    private BigDecimal peOiChange;

    // ==================== LTP (PRICE) ====================
    private BigDecimal ceLtp;  // 🔥 current CE price
    private BigDecimal peLtp;  // 🔥 current PE price

    // ==================== LTP CHANGE (MOMENTUM) ====================
    private BigDecimal ceLtpChange; // 🔥 price movement
    private BigDecimal peLtpChange;

    // ==================== % CHANGE ====================
    private BigDecimal cePct;
    private BigDecimal pePct;

    // ==================== TIME ====================
    private LocalDateTime timestamp;
    @Column(name = "is_atm")
    private Boolean isATM;
}