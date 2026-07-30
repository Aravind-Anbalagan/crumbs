package com.crumbs.trade.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import com.crumbs.trade.entity.RiskConfiguration;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface RiskConfigurationRepository extends JpaRepository<RiskConfiguration, String> {
    // Standard CRUD operations are automatically provided by JpaRepository.
    // The key here is 'String' which matches the strategyName primary key.

    @Modifying
    @Transactional
    @Query("UPDATE RiskConfiguration r SET r.currentPeakPnl = null, r.currentTrailingFloor = null")
    void resetAllTrailingData();
}