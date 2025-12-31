package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.crumbs.trade.utility.TradingMode;

import jakarta.persistence.*;

import lombok.Data;

@Entity
@Table(name = "trading_advice")
@Data
public class TradingAdvice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private LocalDate tradeDate;

    @Enumerated(EnumType.STRING)
    private TradingMode recommendedMode;

    private String direction;      // CE / PE / STRADDLE / STRANGLE
    private BigDecimal strike;

    private LocalDateTime adviceTime;

    private int entryPressure;
    private String entryZone;

    private String status;          // ACTIVE / EXITED
    private LocalDateTime exitTime;
    private String exitReason;
    
}
