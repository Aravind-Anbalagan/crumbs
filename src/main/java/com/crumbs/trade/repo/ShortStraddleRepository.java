package com.crumbs.trade.repo;

import com.crumbs.trade.entity.StraddleIntraday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public interface ShortStraddleRepository extends JpaRepository<StraddleIntraday, Long> {

    /**
     * Fetches the latest tick for a specific symbol (NIFTY/CRUDEOIL).
     * We use a Native Query to ensure we get exactly one record 
     * ordered by the trade_timestamp.
     */
    @Query(value = "SELECT * FROM straddle_intraday " +
                   "WHERE name = :name " +
                   "ORDER BY trade_timestamp DESC " +
                   "LIMIT 1", 
           nativeQuery = true)
    Optional<StraddleIntraday> findLatestByName(@Param("name") String name);
    
 // Find the record where strike is closest to spot (ATM)
    @Query(value = "SELECT * FROM straddle_intraday " +
                   "WHERE name = :name " +
                   "ORDER BY ABS(spot - strike) ASC, trade_timestamp DESC " +
                   "LIMIT 1", nativeQuery = true)
    Optional<StraddleIntraday> findATMBySymbol(@Param("name") String name);

    // Find the latest tick for a specific strike (to monitor active trade)
    @Query(value = "SELECT * FROM straddle_intraday " +
                   "WHERE name = :name AND strike = :strike " +
                   "ORDER BY trade_timestamp DESC " +
                   "LIMIT 1", nativeQuery = true)
    Optional<StraddleIntraday> findLatestBySymbolAndStrike(@Param("name") String name, @Param("strike") BigDecimal strike);
}