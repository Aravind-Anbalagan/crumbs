package com.crumbs.trade.entity;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "trade_execution")
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
    private String exitReason;

    private BigDecimal levelValue;
    private String method;
    private String strength;

    @Column(length = 500)
    private String explanation;
}
