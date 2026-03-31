package com.crumbs.trade.entity;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "alert", indexes = {
    @Index(name = "idx_alert_strategy",  columnList = "strategy_name"),
    @Index(name = "idx_alert_symbol",    columnList = "symbol"),
    @Index(name = "idx_alert_sent_at",   columnList = "sent_at")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_name", nullable = false, length = 50)
    private String strategyName;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "signal_type", length = 30)
    private String signalType;          // VWAP_DOMINANCE_CE / VWAP_DOMINANCE_PE
                                        // CE_PE_CROSSOVER   / PE_CE_CROSSOVER

    @Column(name = "strike")
    private Integer strike;             // ATM strike at time of alert

    // ── live prices at alert time ──────────────────
    @Column(name = "ce_price")  private Double cePrice;
    @Column(name = "pe_price")  private Double pePrice;
    @Column(name = "ce_vwap")   private Double ceVwap;
    @Column(name = "pe_vwap")   private Double peVwap;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(length = 10)
    @Builder.Default
    private String status = "SENT";

    @Column(name = "sent_at")
    @Builder.Default
    private LocalDateTime sentAt = LocalDateTime.now();

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}