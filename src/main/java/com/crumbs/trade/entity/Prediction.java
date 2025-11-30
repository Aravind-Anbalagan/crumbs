package com.crumbs.trade.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "PREDICTION")
@Data
public class Prediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, unique = true)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;   // RELIANCE, HDFCBANK, etc.

    @Column(name = "weight", nullable = false, precision = 10, scale = 4)
    private BigDecimal weight;   // e.g. 9.2500 %

    @Column(name = "prevclose", nullable = true, precision = 15, scale = 4)
    private BigDecimal prevclose;

    @Column(name = "ltp", precision = 15, scale = 4)
    private BigDecimal ltp;  // always updated from API
    
    @Column(name = "exchange", nullable = false)
    private String exchange;   // RELIANCE, HDFCBANK, etc.


}
