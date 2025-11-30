package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "PREDICTION_HISTORY")
@Getter
@Setter
@NoArgsConstructor
public class PredictionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime  timestamp;            

    @Column(name = "current_price", precision = 18, scale = 4)
    private BigDecimal currentPrice;

    @Column(name = "predicted_price", precision = 18, scale = 4)
    private BigDecimal predictedPrice;

    @Column(name = "difference", precision = 18, scale = 4)
    private BigDecimal difference;

    @Column(name = "percentage_move", precision = 10, scale = 4)
    private BigDecimal percentageMove;

    @Column(name = "valid_stocks")
    private Integer validStocks;

    @Column(name = "total_stocks")
    private Integer totalStocks;

    @Column(name = "confidence_score", precision = 6, scale = 2)
    private BigDecimal confidenceScore;

    @Column(name = "sentiment", length = 20)
    private String sentiment;

    @Column(name = "notes", length = 512)
    private String notes;
}
