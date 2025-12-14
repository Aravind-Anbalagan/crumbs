package com.crumbs.trade.repo;

import com.crumbs.trade.entity.Level;
import com.crumbs.trade.entity.TradeExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LevelRepository extends JpaRepository<Level, Long> {

    // ------------------------------------------------
    // 1️⃣ Used when saving fresh snapshot (scheduler)
    // ------------------------------------------------
    void deleteBySymbolAndTimeframe(String symbol, String timeframe);

    // ------------------------------------------------
    // 2️⃣ Used by analysis utility (VERY IMPORTANT)
    // ------------------------------------------------
    List<Level> findBySymbolAndTimeframe(
            String symbol,
            String timeframe
    );

    // ------------------------------------------------
    // 3️⃣ Optional: only latest generated snapshot
    // ------------------------------------------------
    List<Level> findBySymbolAndTimeframeOrderByGeneratedAtDesc(
            String symbol,
            String timeframe
    );
}