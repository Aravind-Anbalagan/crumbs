package com.crumbs.trade.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "risk_configurations")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskConfiguration {

    @Id
    @Column(name = "strategy_name", length = 100)
    private String strategyName;

    @Column(name = "max_loss_limit", precision = 12, scale = 2)
    private BigDecimal maxLossLimit;

    @Column(name = "target_profit", precision = 12, scale = 2)
    private BigDecimal targetProfit;

    @Column(name = "is_trailing_enabled")
    private Boolean isTrailingEnabled; // Must be Boolean object, not primitive boolean

    @Column(name = "activation_threshold", precision = 12, scale = 2)
    private BigDecimal activationThreshold;

    @Column(name = "trail_by", precision = 12, scale = 2)
    private BigDecimal trailBy;

    @Column(name = "profit_step", precision = 12, scale = 2)
    private BigDecimal profitStep;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}