package com.crumbs.trade.repo;

import com.crumbs.trade.entity.Alert;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface AlertRepo extends JpaRepository<Alert, Long> {
    List<Alert> findByStrategyName(String strategyName);
    List<Alert> findByStrategyNameAndSignalType(String strategyName, String signalType);
    List<Alert> findBySentAtBetween(LocalDateTime from, LocalDateTime to);
}