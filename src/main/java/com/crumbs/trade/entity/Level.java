package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "levels",
        indexes = {
                @Index(name = "idx_levels_symbol_tf_active", columnList = "symbol, timeframe, active"),
                @Index(name = "idx_levels_symbol_price",     columnList = "symbol, price")   // ✅ composite — merge lookup uses both
        }
)
public class Level {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --------------------------------
    // Instrument Context
    // --------------------------------
    @Column(nullable = false, length = 25)
    private String symbol;

    @Column(nullable = false, length = 25)
    private String timeframe;

    // --------------------------------
    // Plot Core
    // --------------------------------
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(precision = 12, scale = 2)
    private BigDecimal zoneHigh;

    @Column(precision = 12, scale = 2)
    private BigDecimal zoneLow;

    @Column(nullable = false, length = 12)
    private String type;                // SUPPORT / RESISTANCE

    // --------------------------------
    // Behavior Tracking
    // --------------------------------
    @Builder.Default
    @Column(nullable = false)
    private Integer touches = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer bounce = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer rejection = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer breakout = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer breakdown = 0;

    @Builder.Default
    @Column(nullable = false)
    private Boolean heavyVolume = false;

    // --------------------------------
    // Plot Weight
    // --------------------------------
    @Builder.Default
    @Column(nullable = false)
    private Integer strengthScore = 0;

    // --------------------------------
    // Lifecycle
    // --------------------------------
    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    private LocalDateTime lastTouchedAt;    // updated on each SR scan
    private LocalDateTime lastTradedAt;     // ✅ NEW — stamped when an order fires; drives cooldown guard

    // --------------------------------
    // Audit
    // --------------------------------
    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}