package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "trade_execution",
        indexes = {
                @Index(name = "idx_trade_symbol_tf", columnList = "symbol,timeframe"),
                @Index(name = "idx_trade_status", columnList = "status")
        })
public class TradeExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String timeframe;

    private String tradeType;   // BUY / SELL
    private String status;      // OPEN / CLOSED

    private BigDecimal entryPrice;
    private LocalDateTime entryTime;

    private BigDecimal targetPrice;
    private BigDecimal slPrice;

    private BigDecimal exitPrice;
    private LocalDateTime exitTime;
    private String exitReason;  // TARGET / SL / TRAIL_SL

    private BigDecimal levelValue;
    private String method;      // PRICE_ACTION / FIBO
    private String strength;

    private BigDecimal pnl;     // ✅ PnL stored

    private LocalDateTime lastSignalTime;

    @Column(length = 500)
    private String explanation;
}
