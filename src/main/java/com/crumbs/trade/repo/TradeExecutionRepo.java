package com.crumbs.trade.repo;



import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.crumbs.trade.entity.TradeExecution;

public interface TradeExecutionRepo
        extends JpaRepository<TradeExecution, Long> {

    Optional<TradeExecution>
    findFirstBySymbolAndTimeframeAndStatus(
            String symbol, String timeframe, String status);
}
