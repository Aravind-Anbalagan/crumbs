package com.crumbs.trade.repo;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import com.crumbs.trade.entity.FuturesConfig;

@Repository
public interface FuturesConfigRepo extends JpaRepository<FuturesConfig, Long> {

    @Query("select c from FuturesConfig c where c.active = 'Y'")
    Optional<FuturesConfig> findActive();
    
    @Query("select c from FuturesConfig c")
    FuturesConfig getConfig();   // always one row
}
