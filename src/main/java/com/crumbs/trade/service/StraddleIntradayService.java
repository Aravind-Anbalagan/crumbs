package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.broker.Samco;
import com.crumbs.trade.dto.CombinedChartResponse;
import com.crumbs.trade.dto.StraddlePremiumDto;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.utility.ConditionalLogger;
import com.crumbs.trade.utility.SamcoSessionManager;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StraddleIntradayService {

    private static final Logger baseLogger = LoggerFactory.getLogger(StraddleIntradayService.class);
    private final ConditionalLogger logger = new ConditionalLogger(baseLogger);

    private final StraddleTokenService tokenService;
    private final StraddleMarketDataService marketDataService;
    private final StraddleVwapService vwapService;
    private final StraddlePersistenceService persistenceService;
    private final AngelOne angelOne;
    private final StrategyRepo strategyRepo;
    private final Samco samco;
    private final SamcoSessionManager sessionManager;

    public void getCombineStraddlePremium(String name) {
        try {
            SmartConnect smartconnect = angelOne.signIn();
            if (smartconnect == null) {
                logger.error("Failed to sign in to Angel One");
                return;
            }

            Strategy strategy = strategyRepo.findByName(name);
            if (strategy == null) {
                logger.error("Strategy not found: {}", name);
                return;
            }

            String session = sessionManager.getSession();

			BigDecimal spotPrice = getSpotPrice(name, strategy, session);


            if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
                logger.error("Invalid spot price for {}: {}", name, spotPrice);
                return;
            }

            BigDecimal atmStrike = tokenService.getATMStrike(name, strategy, spotPrice);
            if (atmStrike == null || atmStrike.compareTo(BigDecimal.ZERO) <= 0) return;

            List<StraddlePremiumDto> strikeList = tokenService.getOrBuildStrikeList(name, atmStrike);
            strikeList = tokenService.getAllTokenDetails(strikeList, strategy);

			boolean hasValidTokens = strikeList.stream()
					.anyMatch(dto -> dto.getCeToken() != null
							|| dto.getPeToken() != null);

			if (!hasValidTokens)
				return;

            // OHLC Handling
            persistenceService.resetPrevDayDataIfNewDay();
            Map<String, BigDecimal> strategyHighCache = persistenceService.getPrevHighMap().get(name);
            if (strategyHighCache == null || strategyHighCache.isEmpty()) {
                marketDataService.fetchPreviousDayDataForAllStrikes(strikeList, smartconnect, strategy, persistenceService.getPrevHighMap(), persistenceService.getPrevLowMap(), persistenceService.getPrevCloseMap());
            } else {
                persistenceService.populatePrevDayDataFromCache(strikeList, name);
            }

            // Current Prices
            strikeList = marketDataService.getPriceForAllTheStrikesBatch(strikeList, smartconnect, strategy.getExchange());
            long validPriceCount = strikeList.stream()
                    .filter(this::hasValidPrice)
                    .count();

            if (validPriceCount == 0) return;

            // VWAP
			vwapService.resetVwapIfNewDay();

			List<StraddlePremiumDto> strikesWithPrices = strikeList.stream()
					.filter(this::hasValidPrice).collect(Collectors.toList());

            vwapService.fetchVwapInParallel(strikesWithPrices, smartconnect, strategy.getExchange());

            // Save
            int savedCount = persistenceService.savePriceDetails(strikeList, strategy, spotPrice, atmStrike);
            logger.info("Saved {} records to database for {}", savedCount, name);
        } catch (Exception e) {
            logger.error("Error in getCombineStraddlePremium for {}", name, e);
        }
    }

    public List<StraddlePremiumDto> getPreMarketLTP(String name) {
        try {
            SmartConnect smartconnect = angelOne.signIn();
            if (smartconnect == null) return new ArrayList<>();

            Strategy strategy = strategyRepo.findByName(name);
            if (strategy == null) return new ArrayList<>();

            String session = sessionManager.getSession();
            BigDecimal spotPrice = null;
            if ("NIFTY".equalsIgnoreCase(name) || "SENSEX".equalsIgnoreCase(name)) {
                spotPrice = samco.getIndexPrice(session, name);
            } else if ("CRUDEOIL".equalsIgnoreCase(name) || "CRUDEOILM".equalsIgnoreCase(name) || "NATURALGAS".equalsIgnoreCase(name)) {
                spotPrice = samco.getLtp(session, strategy.getExchange(), tokenService.getSymbolByName(name));
            }
            if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) return new ArrayList<>();

            BigDecimal atmStrike = tokenService.getATMStrike(name, strategy, spotPrice);
            if (atmStrike == null || atmStrike.compareTo(BigDecimal.ZERO) <= 0) return new ArrayList<>();

            List<StraddlePremiumDto> strikeList = tokenService.getOrBuildStrikeList(name, atmStrike);
            strikeList = tokenService.getAllTokenDetails(strikeList, strategy);

            strikeList = strikeList.stream().filter(dto -> dto.getStrikePrice().compareTo(atmStrike) == 0).collect(Collectors.toList());
            if (strikeList.isEmpty()) return new ArrayList<>();

            Thread.sleep(2000); 
            strikeList = marketDataService.getPriceForAllTheStrikesBatch(strikeList, smartconnect, strategy.getExchange());

            persistenceService.resetPrevDayDataIfNewDay();
            Map<String, BigDecimal> strategyHighCache = persistenceService.getPrevHighMap().get(name);
            if (strategyHighCache == null || strategyHighCache.isEmpty()) {
                marketDataService.fetchPreviousDayDataForAllStrikes(strikeList, smartconnect, strategy, persistenceService.getPrevHighMap(), persistenceService.getPrevLowMap(), persistenceService.getPrevCloseMap());
            } else {
                persistenceService.populatePrevDayDataFromCache(strikeList, name);
            }

            persistenceService.getPrevHighMap().remove(name);
            persistenceService.getPrevLowMap().remove(name);
            return strikeList;
        } catch (Exception e) {
            logger.error("Error in getPreMarketLTP for {}", name, e);
            return new ArrayList<>();
        }
    }

    public CombinedChartResponse getStraddleCombinedChart(String name, String expiry, BigDecimal ceStrike, BigDecimal peStrike) {
        return persistenceService.getStraddleCombinedChart(name, expiry, ceStrike, peStrike);
    }
    
    private BigDecimal getSpotPrice(
            String name,
            Strategy strategy,
            String session) {

        if ("NIFTY".equalsIgnoreCase(name)
                || "SENSEX".equalsIgnoreCase(name) || name.contains("BANK")) {
            return samco.getIndexPrice(session, name);
        }

        if ("CRUDEOIL".equalsIgnoreCase(name)
                || "CRUDEOILM".equalsIgnoreCase(name)
                || "NATURALGAS".equalsIgnoreCase(name)) {

            return samco.getLtp(
                    session,
                    strategy.getExchange(),
                    tokenService.getSymbolByName(name)
            );
        }

        return null;
    }
    private boolean hasValidPrice(StraddlePremiumDto dto) {
        return (dto.getCePrice() != null
                && dto.getCePrice().compareTo(BigDecimal.ZERO) > 0)
            || (dto.getPePrice() != null
                && dto.getPePrice().compareTo(BigDecimal.ZERO) > 0);
    }
}