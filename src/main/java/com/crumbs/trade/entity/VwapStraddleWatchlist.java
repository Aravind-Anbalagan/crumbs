package com.crumbs.trade.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "vwap_straddle_watchlist",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_vwap_straddle_day",
                columnNames = {"trade_date", "symbol", "expiry", "strike"}
        )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VwapStraddleWatchlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "symbol", nullable = false, length = 50)
    private String symbol;

    @Column(name = "expiry", nullable = false)
    private LocalDate expiry;

    @Column(name = "strike", nullable = false, precision = 12, scale = 2)
    private BigDecimal strike;

    @Column(name = "ce_token", nullable = false, length = 50)
    private String ceToken;

    @Column(name = "pe_token", nullable = false, length = 50)
    private String peToken;

    @Builder.Default
    @Column(name = "entry_hit_target", nullable = false)
    private Integer entryHitTarget = 5;

    @Builder.Default
    @Column(name = "exit_hit_target", nullable = false)
    private Integer exitHitTarget = 5;

    @Builder.Default
    @Column(name = "current_hits", nullable = false)
    private Integer currentHits = 0;

    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private String status = "WAITING";

    @Column(name = "entry_cp", precision = 12, scale = 2)
    private BigDecimal entryCp;

    @Builder.Default
    @Column(name = "alert_flag", nullable = false, length = 1)
    private String alertFlag = "Y";

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (tradeDate == null) tradeDate = now.toLocalDate();
        if (entryHitTarget == null) entryHitTarget = 5;
        if (exitHitTarget == null) exitHitTarget = 5;
        if (currentHits == null) currentHits = 0;
        if (status == null) status = "WAITING";
        if (alertFlag == null) alertFlag = "Y";
        if (isActive == null) isActive = true;
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}