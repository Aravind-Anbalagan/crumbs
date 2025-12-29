package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "FUTURES_FILTER")
@Data
public class FuturesFilter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private BigDecimal lastExpiryPrice;
    private BigDecimal lastTradedPrice;
    private BigDecimal percentMove;
    private String direction;
    private String status;

    private LocalDate lastExpiryDate;


    private LocalDateTime lastTradedDate;
}
