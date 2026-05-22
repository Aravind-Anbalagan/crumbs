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

    // --- NEW: STRATEGY CATEGORIZATION ---
    @Column(name = "strategy_type", length = 20, nullable = false)
    private String strategyType; // e.g., 'OPTION_SELL', 'OPTION_BUY', 'STRADDLE'

    @Column(name = "max_loss_limit", precision = 12, scale = 2)
    private BigDecimal maxLossLimit;

    @Column(name = "target_profit", precision = 12, scale = 2)
    private BigDecimal targetProfit;

    @Column(name = "smart_risk_flag", length = 1, nullable = false)
    private String smartRiskFlag; 

    @Column(name = "velocity_panic_drop", precision = 12, scale = 2)
    private BigDecimal velocityPanicDrop;

    @Column(name = "milestone_percent", precision = 5, scale = 2)
    private BigDecimal milestonePercent; 

    @Column(name = "breakeven_floor", precision = 12, scale = 2)
    private BigDecimal breakevenFloor; 

    @Column(name = "trailing_activation", precision = 12, scale = 2)
    private BigDecimal trailingActivation; 

    @Column(name = "trailing_drawdown_pct", precision = 5, scale = 2)
    private BigDecimal trailingDrawdownPct; 

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}