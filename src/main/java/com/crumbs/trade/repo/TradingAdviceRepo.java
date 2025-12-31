package com.crumbs.trade.repo;


import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crumbs.trade.entity.TradingAdvice;

public interface TradingAdviceRepo
        extends JpaRepository<TradingAdvice, Long> {

    // =====================================================
    // 1️⃣ FIND ACTIVE ADVICE (used by IntradayAdvisorScheduler)
    // =====================================================
    Optional<TradingAdvice> findFirstBySymbolAndTradeDateAndStatus(
            String symbol,
            LocalDate tradeDate,
            String status   // "ACTIVE"
    );

    // =====================================================
    // 2️⃣ FIND ALL ADVICES FOR A DAY (used by EOD Audit)
    // =====================================================
    List<TradingAdvice> findByTradeDate(LocalDate tradeDate);

    // =====================================================
    // 3️⃣ FIND ALL ADVICES FOR A SYMBOL & DAY (optional)
    // =====================================================
    List<TradingAdvice> findBySymbolAndTradeDate(
            String symbol,
            LocalDate tradeDate
    );

    // =====================================================
    // 4️⃣ SAFETY CHECK (optional but recommended)
    // Prevent multiple ACTIVE advices accidentally
    // =====================================================
    long countBySymbolAndTradeDateAndStatus(
            String symbol,
            LocalDate tradeDate,
            String status
    );
    
   

    // ---------------------------------------------
    // 🔥 ADD THIS METHOD (for cooldown logic)
    // ---------------------------------------------
    Optional<TradingAdvice>
        findTopBySymbolAndTradeDateOrderByAdviceTimeDesc(
            String symbol,
            LocalDate tradeDate
        );

   
}

