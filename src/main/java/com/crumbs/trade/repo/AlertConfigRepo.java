package com.crumbs.trade.repo;

import com.crumbs.trade.entity.AlertConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AlertConfigRepo extends JpaRepository<AlertConfig, Long> {
    Optional<AlertConfig> findByStrategyName(String strategyName);
}