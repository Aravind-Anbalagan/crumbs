package com.crumbs.trade.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "alert_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_name", nullable = false, unique = true, length = 50)
    private String strategyName;          // e.g. "STOCK_ALERT", "MA_HIERARCHY"

    @Column(name = "save_enabled", nullable = false)
    @Builder.Default
    private Boolean saveEnabled = false;  // default: don't save

    @Column(length = 200)
    private String description;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}