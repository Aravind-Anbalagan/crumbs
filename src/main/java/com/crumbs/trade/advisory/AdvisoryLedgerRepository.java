package com.crumbs.trade.advisory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AdvisoryLedgerRepository extends JpaRepository<AdvisoryLedger, Long> {


 // 1. ENGINE USE: Finds the most recent active state for a specific stock (e.g., NIFTY)
    Optional<AdvisoryLedger> findTopBySymbolAndStatusOrderByTimestampDesc(String symbol, String status);

    // 2. UI DASHBOARD: Fetches all currently active trades across all symbols instantly
    List<AdvisoryLedger> findByStatus(String status);

    // 3. UI DRILL-DOWN: Fetches the entire history of a symbol to render the Timeline
    List<AdvisoryLedger> findBySymbolOrderByTimestampDesc(String symbol);

    // NEW: Filter by current cycle
    List<AdvisoryLedger> findByTimestampBetween(LocalDateTime start, LocalDateTime end);
    List<AdvisoryLedger> findByStatusAndTimestampBetween(String status, LocalDateTime start, LocalDateTime end);
}