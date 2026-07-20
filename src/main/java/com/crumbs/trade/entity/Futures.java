package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "FUTURES")
@Data  // ✅ Lombok: generates getters, setters, toString, equals, hashCode
public class Futures {

	   
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, unique = true)
    private Long id;
    
    @Column(name = "name", nullable = false, length = 200)
    private String name;  // Full company name
    
    @Column(name = "sector", nullable = false, length = 100)
    private String sector;
    
    @Column(name = "industry", nullable = false, length = 150)
    private String industry;
    
    // Index Membership Flags
    @Column(name = "is_nifty_50", nullable = false)
    private Boolean isNifty50 = false;
    
    @Column(name = "is_nifty_next_50", nullable = false)
    private Boolean isNiftyNext50 = false;
    
    @Column(name = "is_nifty_100", nullable = false)
    private Boolean isNifty100 = false;
    
    @Column(name = "is_nifty_200", nullable = false)
    private Boolean isNifty200 = false;
    
    @Column(name = "is_nifty_500", nullable = false)
    private Boolean isNifty500 = false;
    
 // ✅ NEW: Persisted Caching Columns for Monthly Expiry OHLC Structure
    @Column(name = "expiry_high")
    private BigDecimal expiryHigh;

    @Column(name = "expiry_low")
    private BigDecimal expiryLow;

    @Column(name = "expiry_close")
    private BigDecimal expiryClose;

    @Column(name = "last_expiry_date")
    private LocalDate lastExpiryDate;
    @Column(name = "last_updated")
    private java.time.LocalDateTime lastUpdated;
     
}