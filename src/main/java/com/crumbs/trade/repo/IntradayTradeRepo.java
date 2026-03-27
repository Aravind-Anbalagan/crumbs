package com.crumbs.trade.repo;

import com.crumbs.trade.entity.IntradayTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface IntradayTradeRepo extends JpaRepository<IntradayTrade, Long> {

    // ✅ FIX #3: Changed return type from Optional to List so we can detect duplicates
    //            (was: Optional<IntradayTrade> findBySymbolAndStatus)
    List<IntradayTrade> findAllBySymbolAndStatus(String symbol, String status);

    // ✅ FIX #2: Needed by EOD square-off to fetch all open trades across all symbols
    List<IntradayTrade> findAllByStatus(String status);

    // Keep the original Optional version if other parts of your code use it
    Optional<IntradayTrade> findBySymbolAndStatus(String symbol, String status);
    
    @Modifying
    @Query("delete from IntradayTrade I")
    void deleteAll();
}