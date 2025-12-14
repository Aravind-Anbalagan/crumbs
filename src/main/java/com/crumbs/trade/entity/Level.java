package com.crumbs.trade.entity;


import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(
        name = "levels",
        indexes = {
                @Index(name = "idx_levels_symbol_tf", columnList = "symbol, timeframe"),
                @Index(name = "idx_levels_method_seq", columnList = "method, seq"),
                @Index(name = "idx_levels_value", columnList = "levelValue")
        }
)
public class Level {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --------------------------------
    // Instrument Context
    // --------------------------------
    @Column(nullable = false, length = 20)
    private String symbol;              // NIFTY, BANKNIFTY

    @Column(nullable = false, length = 20)
    private String timeframe;           // 5M, 15M, HOURLY, DAILY

    // --------------------------------
    // SR Ordering & Direction
    // --------------------------------
    @Column(nullable = false)
    private Integer seq;
    /*
     * seq > 0  => SUPPORT  (1 strongest, 5 weakest)
     * seq < 0  => RESISTANCE (-1 strongest, -5 weakest)
     * seq != 0 => enforced by validation
     */

    // --------------------------------
    // Source of Level
    // --------------------------------
    @Column(nullable = false, length = 20)
    private String method;              // PRICE_ACTION / FIBO

    // --------------------------------
    // Price Level (COMMON COLUMN)
    // --------------------------------
    @Column(name = "level_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal levelValue;

    // --------------------------------
    // Fibo / Strength Metadata
    // --------------------------------
    @Column(length = 10)
    private String strength;            // CRITICAL / MODERATE / WEAK

    private Integer touches;             // No. of touches (Fibo)

    @Column(length = 120)
    private String label;                // API label text

    // --------------------------------
    // Audit Fields
    // --------------------------------
    @Column(nullable = false)
    private LocalDateTime generatedAt;   // API calculation time

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
