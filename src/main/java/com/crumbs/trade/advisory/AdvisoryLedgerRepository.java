package com.crumbs.trade.advisory;




import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AdvisoryLedgerRepository extends JpaRepository<AdvisoryLedger, Long> {

    // Fetch the single active state for a specific stock
    Optional<AdvisoryLedger> findTopBySymbolAndStatusOrderByTimestampDesc(String symbol, String status);

}