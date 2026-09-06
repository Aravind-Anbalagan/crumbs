package com.crumbs.trade.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "strategy_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyConfig {

    @Id
    private Long id; // Hardcode to 1 in your DB

    private String defaultInterval; // e.g., "FIFTEEN_MINUTE"
    private int maPeriod;           // e.g., 20
    private int rsiPeriod;          // e.g., 14
    private double maProximity;     // e.g., 50.0
}