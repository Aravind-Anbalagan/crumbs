package com.crumbs.trade.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "intraday_trades")
public class IntradayTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String name;
    private String exchange;

    private String position; // BUY / SELL

    private BigDecimal entryPrice;
    private LocalDateTime entryTime;

    private BigDecimal exitPrice;
    private LocalDateTime exitTime;

    private BigDecimal pnl;

    private String status; // OPEN / CLOSED

    @Column(columnDefinition = "TEXT")
    private String entryReason;

    @Column(columnDefinition = "TEXT")
    private String exitReason;

    private String state; // ALIGNED_BUY / ALIGNED_SELL / NOT_ALIGNED

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}