package com.crumbs.trade.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "STRATEGY")
public class Strategy {

    @Id
    @Column(name = "id", nullable = false, unique = true)
    private Long id;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "symbol1")
    private String symbol1;

    @Column(name = "name")
    private String name;

    @Column(name = "active")
    private String active;

    @Column(name = "execute")
    private String execute;

    @Column(name = "exchange")
    private String exchange;

    @Column(name = "token")
    private String token;

    @Column(name = "tradingsymbol")
    private String tradingsymbol;

    @Column(name = "points")
    private int points;

    @Column(name = "candlestick")
    private int candlestick;

    @Column(name = "expiry")
    private String expiry;

    @Column(name = "live")
    private String live;

    @Column(name = "papertrade")
    private String papertrade;

    @Column(name = "dayCandle")
    private String dayCandle;

    @Column(name = "maxloss")
    private String maxloss;

    @Column(name = "alert", nullable = false, length = 1)
    private String alert = "N";
    
    @Column(name = "enable_logging")
    private String enableLogging = "N"; // Default to "N" (no logging)
}
