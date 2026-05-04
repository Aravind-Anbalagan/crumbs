package com.crumbs.trade.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
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
    private String symbol; 

    @Column(name = "trading_symbol")
    private String tradingSymbol; 

    @Column(name = "token")
    private String token; 

    @Column(name = "expiry_date", nullable = false)
    private String expiryDate;

    // STRIKE is money, use BigDecimal
    @Column(name = "strike_price", nullable = false, precision = 10, scale = 4)
    private BigDecimal strikePrice;

    @Column(name = "option_type", nullable = false, length = 5)
    private String optionType; 

    // SPOT is money, use BigDecimal
    @Column(name = "spot_price", precision = 10, scale = 4)
    private BigDecimal spotPrice;

    // LTP is money, use BigDecimal
    @Column(name = "last_traded_price", precision = 10, scale = 4)
    private BigDecimal ltp;

    // GREEKS & IV are math, use Double
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