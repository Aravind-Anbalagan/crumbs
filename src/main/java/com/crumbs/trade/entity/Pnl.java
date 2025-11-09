package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "Pnl")
public class Pnl {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    private String name;
    private BigDecimal totalPoints;
    private int lotSize;
    private BigDecimal netPnl;
    private int profitTrades;
    private int lossTrades;
    private int totalTrades;
    private LocalDate tradeDate = LocalDate.now();
    private LocalDateTime createdAt = LocalDateTime.now();
}
