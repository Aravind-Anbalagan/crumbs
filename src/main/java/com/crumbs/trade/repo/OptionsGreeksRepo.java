package com.crumbs.trade.repo;

import com.crumbs.trade.entity.OptionsGreeks;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OptionsGreeksRepo extends JpaRepository<OptionsGreeks, Long> {

    // --- ADD THIS METHOD DECLARATION ---
    // This tells the Java compiler that this method exists, and Spring Boot 
    // will automatically generate the SQL for it at runtime.
    Optional<OptionsGreeks> findTopBySymbolAndStrikePriceAndOptionTypeOrderByTimestampDesc(
            String symbol, String strikePrice, String optionType
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
}