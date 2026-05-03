package com.crumbs.trade.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Entity
@Table(name = "options_greeks", indexes = {
    @Index(name = "idx_lookup_latest", columnList = "symbol, strike_price, option_type, timestamp DESC")
})
@Data
public class OptionsGreeks {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "symbol", nullable = false)
    private String symbol; // Base symbol: NIFTY / CRUDEOIL

    @Column(name = "trading_symbol")
    private String tradingSymbol; // Full symbol: NIFTY07MAY2624100CE

    @Column(name = "token")
    private String token; // The numeric instrument token

    @Column(name = "expiry_date", nullable = false)
    private String expiryDate;

    @Column(name = "strike_price", nullable = false)
    private String strikePrice;

    @Column(name = "option_type", nullable = false, length = 5)
    private String optionType; 

    @Column(name = "spot_price")
    private Double spotPrice;

    @Column(name = "last_traded_price")
    private Double ltp;

    @Column(name = "implied_volatility")
    private Double impliedVolatility;

    private Double delta;
    private Double gamma;
    private Double theta;
    private Double vega;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "best_bids", columnDefinition = "jsonb")
    private String bestBids; 

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "best_asks", columnDefinition = "jsonb")
    private String bestAsks;
}