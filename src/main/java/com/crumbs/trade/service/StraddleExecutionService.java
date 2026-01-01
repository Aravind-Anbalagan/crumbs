package com.crumbs.trade.service;


import org.slf4j.LoggerFactory;
import com.crumbs.trade.repo.StrategyRepo;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StraddleExecutionService {

    private final StraddleIntradayService straddleIntradayService;
    private final StrategyRepo strategyRepo;

    public void execute(String name) {

        try {
            if (!isActive("STRADDLE_PREMIUM")) {
                return;
            }

            straddleIntradayService.getCombineStraddlePremium(name);

        } catch (Exception e) {
            // IMPORTANT: never let scheduler die
            LoggerFactory.getLogger(getClass())
                    .error("❌ Straddle execution failed for {}", name, e);
        }
    }

    private boolean isActive(String strategy) {
        return "Y".equalsIgnoreCase(
                strategyRepo.findByName(strategy).getActive()
        );
    }
}
