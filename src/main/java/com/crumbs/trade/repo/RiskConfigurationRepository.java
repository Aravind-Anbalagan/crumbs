package com.crumbs.trade.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.crumbs.trade.entity.RiskConfiguration;

@Repository
public interface RiskConfigurationRepository extends JpaRepository<RiskConfiguration, String> {
    // Standard CRUD operations are automatically provided by JpaRepository.
    // The key here is 'String' which matches the strategyName primary key.
}