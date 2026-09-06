package com.crumbs.trade.repo;

import com.crumbs.trade.entity.StrategyConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StrategyConfigRepo extends JpaRepository<StrategyConfig, Long> {
}