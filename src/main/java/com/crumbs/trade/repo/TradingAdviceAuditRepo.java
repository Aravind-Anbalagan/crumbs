package com.crumbs.trade.repo;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crumbs.trade.entity.TradingAdviceAudit;

public interface TradingAdviceAuditRepo
        extends JpaRepository<TradingAdviceAudit, Long> {

    // =====================================================
    // 1️⃣ FIND ALL AUDITS FOR A DAY (EOD review)
    // =====================================================
    List<TradingAdviceAudit> findByTradeDate(LocalDate tradeDate);

    // =====================================================
    // 2️⃣ FIND ALL AUDITS FOR SYMBOL & DAY
    // =====================================================
    List<TradingAdviceAudit> findBySymbolAndTradeDate(
            String symbol,
            LocalDate tradeDate
    );

    // =====================================================
    // 3️⃣ FIND AUDIT FOR A GIVEN ADVICE
    // =====================================================
    List<TradingAdviceAudit> findByAdviceId(Long adviceId);
}
