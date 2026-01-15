package com.crumbs.trade.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.FuturesConfig;

@Repository
public interface FuturesConfigRepo extends JpaRepository<FuturesConfig, Long> {
    
    /**
     * Find single active config (returns first one found)
     */
    @Query("SELECT c FROM FuturesConfig c WHERE c.active = 'Y'")
    Optional<FuturesConfig> findActive();
    
    /**
     * Get single config (backward compatibility - assumes only one row)
     */
    @Query("SELECT c FROM FuturesConfig c")
    FuturesConfig getConfig();
    
    /**
     * Find ALL configs by active status
     * Use this to get all active configs
     */
    List<FuturesConfig> findByActive(String active);
    
    /**
     * Find config by active status AND index type
     */
    Optional<FuturesConfig> findByActiveAndIndexType(String active, String indexType);
    
    /**
     * Find config by index type only
     */
    Optional<FuturesConfig> findByIndexType(String indexType);
}