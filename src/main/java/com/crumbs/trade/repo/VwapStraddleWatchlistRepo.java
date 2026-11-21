package com.crumbs.trade.repo;

import com.crumbs.trade.entity.VwapStraddleWatchlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface VwapStraddleWatchlistRepo extends JpaRepository<VwapStraddleWatchlist, Long> {

    List<VwapStraddleWatchlist> findByTradeDateAndIsActiveTrueOrderByIdAsc(LocalDate tradeDate);

    List<VwapStraddleWatchlist> findByTradeDateOrderByIdAsc(LocalDate tradeDate);

    boolean existsByTradeDateAndSymbolIgnoreCaseAndExpiryAndStrike(
            LocalDate tradeDate, String symbol, LocalDate expiry, BigDecimal strike);

    // Read-only lookups against existing `indexes` master table
    @Query(value = """
        SELECT DISTINCT UPPER(TRIM(name))
        FROM indexes
        WHERE TRIM(exchange) = 'NFO'
          AND (UPPER(TRIM(symbol)) LIKE '%CE' OR UPPER(TRIM(symbol)) LIKE '%PE')
          AND NULLIF(TRIM(strike), '') IS NOT NULL
          AND CAST(NULLIF(TRIM(strike), '') AS NUMERIC) > 0
        ORDER BY 1
        """, nativeQuery = true)
    List<String> findDistinctNfoSymbols();

    @Query(value = """
        SELECT expiry, CAST(NULLIF(TRIM(strike), '') AS NUMERIC)
        FROM indexes
        WHERE TRIM(exchange) = 'NFO'
          AND UPPER(TRIM(name)) = UPPER(TRIM(:symbol))
          AND CAST(NULLIF(TRIM(strike), '') AS NUMERIC) > 0
          AND (UPPER(TRIM(symbol)) LIKE '%CE' OR UPPER(TRIM(symbol)) LIKE '%PE')
        GROUP BY expiry, CAST(NULLIF(TRIM(strike), '') AS NUMERIC)
        HAVING COUNT(DISTINCT RIGHT(UPPER(TRIM(symbol)), 2)) = 2
        """, nativeQuery = true)
    List<Object[]> findNfoExpiriesAndStrikesBySymbol(@Param("symbol") String symbol);

    @Query(value = """
        SELECT expiry, symbol, token
        FROM indexes
        WHERE TRIM(exchange) = 'NFO'
          AND UPPER(TRIM(name)) = UPPER(TRIM(:symbol))
          AND (UPPER(TRIM(symbol)) LIKE '%CE' OR UPPER(TRIM(symbol)) LIKE '%PE')
          AND NULLIF(TRIM(strike), '') IS NOT NULL
          AND ABS(CAST(NULLIF(TRIM(strike), '') AS NUMERIC) - :scaledStrike) < 0.01
        """, nativeQuery = true)
    List<Object[]> findNfoOptionLegs(
            @Param("symbol") String symbol,
            @Param("scaledStrike") BigDecimal scaledStrike);

    @Modifying
    @Query("delete from VwapStraddleWatchlist")
    void deleteAll();

    boolean existsByTradeDateAndSymbolIgnoreCase(LocalDate tradeDate, String symbol);
}