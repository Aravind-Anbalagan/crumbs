package com.crumbs.trade.repo;

import com.crumbs.trade.entity.OptionsGreeks;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OptionsGreeksRepo extends JpaRepository<OptionsGreeks, Long> {

	@Modifying
	@Query("delete from OptionsGreeks o")
	void deleteAll();
	
    // --- ADD THIS METHOD DECLARATION ---
    // This tells the Java compiler that this method exists, and Spring Boot 
    // will automatically generate the SQL for it at runtime.
    Optional<OptionsGreeks> findTopBySymbolAndStrikePriceAndOptionTypeOrderByTimestampDesc(
            String symbol, BigDecimal strikePrice, String optionType
    );

    // Previously added helper query (keep this if you still have it)
    @Query("""
        SELECT o FROM OptionsGreeks o 
        WHERE o.symbol = :symbol 
          AND o.expiryDate = :expiry 
          AND o.strikePrice = :strike 
          AND o.timestamp >= :cutoffTime
        ORDER BY o.timestamp DESC
    """)
    List<OptionsGreeks> findLatestStraddleLegs(
            @Param("symbol") String symbol,
            @Param("expiry") String expiry,
            @Param("strike") String strike,
            @Param("cutoffTime") LocalDateTime cutoffTime
    );
    
    @Query(value = "SELECT c.timestamp, c.last_traded_price, p.last_traded_price, (c.last_traded_price + p.last_traded_price), " +
            "c.implied_volatility, c.delta, c.gamma, c.theta, c.vega, " +
            "p.implied_volatility, p.delta, p.gamma, p.theta, p.vega " +
            "FROM options_greeks c JOIN options_greeks p " +
            "ON c.symbol = p.symbol " +
            "AND ABS(EXTRACT(EPOCH FROM (c.timestamp - p.timestamp))) < 0.5 " + 
            "WHERE c.symbol = :symbol " +
            "AND c.strike_price = :ceStrike AND c.option_type = 'CE' " +
            "AND p.strike_price = :peStrike AND p.option_type = 'PE' " +
            "ORDER BY c.timestamp ASC", 
            nativeQuery = true) // <-- THIS IS THE CRITICAL FLAG
     List<Object[]> findHistoricalMultiLegWithGreeks(
             @Param("symbol") String symbol, 
             @Param("ceStrike") BigDecimal ceStrike, 
             @Param("peStrike") BigDecimal peStrike
     );
     
 

     // 2. Used by OptionStrategyAnalyzer (The new Advisory engine)
     // Fetches the option chain history to find the highest OI Walls
     List<OptionsGreeks> findBySymbolOrderByTimestampDesc(String symbol);
}