package com.crumbs.trade.entity;


import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "alert", indexes = {
    @Index(name = "idx_alert_strategy", columnList = "strategy_name"),
    @Index(name = "idx_alert_sent_at",  columnList = "sent_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_name", nullable = false, length = 50)
    private String strategyName;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    @Column(name = "signal_type", length = 20)
    private String signalType;

    @Column(length = 10)
    @Builder.Default
    private String status = "SENT";

    @Column(name = "sent_at")
    @Builder.Default
    private LocalDateTime sentAt = LocalDateTime.now();

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
