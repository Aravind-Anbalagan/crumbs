package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.CombinedChartPoint;
import com.crumbs.trade.dto.CombinedChartResponse;
import com.crumbs.trade.dto.StraddlePremiumDto;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StraddleIntradayRepo;
import com.crumbs.trade.utility.ConditionalLogger;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StraddlePersistenceService {

    private static final Logger baseLogger = LoggerFactory.getLogger(StraddlePersistenceService.class);
    private final ConditionalLogger logger = new ConditionalLogger(baseLogger);

    private final StraddleIntradayRepo straddleIntradayRepo;
    private final StraddleAlertService alertService;
    private final StraddleVwapService vwapService;

    @Getter private final Map<String, Map<String, BigDecimal>> prevHighMap = new HashMap<>();
    @Getter private final Map<String, Map<String, BigDecimal>> prevLowMap = new HashMap<>();
    @Getter private final Map<String, Map<String, BigDecimal>> prevCloseMap = new HashMap<>();
    public LocalDate prevDayDataDate = null;

    // =========================================================================
    // ✅ ADDED: Missing method required by your existing callers
    // =========================================================================
    public List<StraddleIntraday> getTimeSeriesByStrike(String name, String expiry, BigDecimal strike) {
        List<StraddleIntraday> records = straddleIntradayRepo
            .findByNameAndExpiryAndStrikeOrderByTimestampDesc(name, expiry, strike);
        Collections.reverse(records);
        return records;
    }

    public int savePriceDetails(List<StraddlePremiumDto> strikeList, Strategy strategy, BigDecimal spotPrice, BigDecimal atmStrike) {
        int count = 0;
        LocalDateTime timestamp = LocalDateTime.now(ZoneId.of("Asia/Kolkata")).withNano(0);

        for (StraddlePremiumDto dto : strikeList) {
            if (dto.getCeToken() == null && dto.getPeToken() == null) continue;
            BigDecimal ce = dto.getCePrice() != null ? dto.getCePrice() : BigDecimal.ZERO;
            BigDecimal pe = dto.getPePrice() != null ? dto.getPePrice() : BigDecimal.ZERO;

            if (ce.compareTo(BigDecimal.ZERO) <= 0 && pe.compareTo(BigDecimal.ZERO) <= 0) continue;
            if (dto.getStrikePrice() == null || dto.getStrikePrice().compareTo(BigDecimal.ZERO) <= 0) continue;

            StraddleIntraday entity = new StraddleIntraday();
            entity.setName(strategy.getName());
            entity.setExpiry(strategy.getExpiry());
            entity.setStrike(dto.getStrikePrice());
            entity.setTimestamp(timestamp);

            entity.setCePrice(ce);
            entity.setPePrice(pe);
            entity.setSpot(spotPrice);
            entity.setCeIV(dto.getCeIv());
            entity.setPeIV(dto.getPeIv());
            entity.setCeVwap(dto.getCeVwap());
            entity.setPeVwap(dto.getPeVwap());

            entity.setCeVolume(dto.getCeVolume());
            entity.setPeVolume(dto.getPeVolume());
            entity.setCeOi(dto.getCeOI());
            entity.setPeOi(dto.getPeOI());

            entity.setCePrevHigh(dto.getCePrevHigh());
            entity.setCePrevLow(dto.getCePrevLow());
            entity.setPePrevHigh(dto.getPePrevHigh());
            entity.setPePrevLow(dto.getPePrevLow());
            entity.setCePrevClose(dto.getCePrevClose());
            entity.setPePrevClose(dto.getPePrevClose());
            entity.setCombinedPrevClose(dto.getCombinedPrevClose());

            BigDecimal combinedIV = vwapService.calculateCombinedIV(dto.getCeIv(), dto.getPeIv());
            entity.setCombinedIv(combinedIV);
            dto.setCombinedIv(combinedIV); 

            alertService.detectCrossoverEvent(dto, strategy.getName(), timestamp);
            entity.setCeCrossoverAbove(dto.isCeCrossoverAbove());
            entity.setPeCrossoverAbove(dto.isPeCrossoverAbove());

            BigDecimal ceVwap = dto.getCeVwap() != null ? dto.getCeVwap() : BigDecimal.ZERO;
            BigDecimal peVwap = dto.getPeVwap() != null ? dto.getPeVwap() : BigDecimal.ZERO;
            entity.setCombinedVwap(ceVwap.add(peVwap));

            BigDecimal combinedPremium = ce.add(pe);
            entity.setCombinedPremium(combinedPremium);

            BigDecimal ceIntrinsic = spotPrice.subtract(dto.getStrikePrice()).max(BigDecimal.ZERO);
            BigDecimal peIntrinsic = dto.getStrikePrice().subtract(spotPrice).max(BigDecimal.ZERO);
            entity.setCeIntrinsic(ceIntrinsic);
            entity.setPeIntrinsic(peIntrinsic);
            entity.setCeExtrinsic(ce.subtract(ceIntrinsic).max(BigDecimal.ZERO));
            entity.setPeExtrinsic(pe.subtract(peIntrinsic).max(BigDecimal.ZERO));

            entity.setCeOpenPrice(dto.getCeOpenPrice());
            entity.setPeOpenPrice(dto.getPeOpenPrice());
            if (dto.getCeOpenPrice() != null && dto.getPeOpenPrice() != null) {
                entity.setCombinedOpenPrice(dto.getCeOpenPrice().add(dto.getPeOpenPrice()));
            }

            entity.setAvgPrice(combinedPremium.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP));
            if (dto.getCeToken() != null && dto.getPeToken() != null) {
                entity.setCeToken(dto.getCeToken().getToken());
                entity.setCeSymbol(dto.getCeToken().getSymbol());
                entity.setPeToken(dto.getPeToken().getToken());
                entity.setPeSymbol(dto.getPeToken().getSymbol());
            }

            try {
                straddleIntradayRepo.save(entity);
                count++;
                alertService.checkAndSendAlerts(entity);
            } catch (Exception e) {
                logger.error("Failed to save record for strike {}: {}", dto.getStrikePrice(), e.getMessage());
            }
        }
        return count;
    }

    public void resetPrevDayDataIfNewDay() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        if (prevDayDataDate == null || !prevDayDataDate.equals(today)) {
            prevHighMap.clear();
            prevLowMap.clear();
            prevCloseMap.clear(); 
            prevDayDataDate = today;
            logger.info("Previous day high/low cache reset for new trading day: {}", today);
        }
    }

    public void populatePrevDayDataFromCache(List<StraddlePremiumDto> strikeList, String strategyName) {
        Map<String, BigDecimal> highCache = prevHighMap.get(strategyName);
        Map<String, BigDecimal> lowCache = prevLowMap.get(strategyName);
        Map<String, BigDecimal> closeCache = prevCloseMap.get(strategyName);

        if (highCache == null || lowCache == null || closeCache == null) return;

        for (StraddlePremiumDto dto : strikeList) {
            if (dto.getCeToken() != null) {
                String t = dto.getCeToken().getToken();
                dto.setCePrevHigh(highCache.get(t));
                dto.setCePrevLow(lowCache.get(t));
                dto.setCePrevClose(closeCache.get(t));
            }
            if (dto.getPeToken() != null) {
                String t = dto.getPeToken().getToken();
                dto.setPePrevHigh(highCache.get(t));
                dto.setPePrevLow(lowCache.get(t));
                dto.setPePrevClose(closeCache.get(t));
            }
            if (dto.getCePrevClose() != null && dto.getPePrevClose() != null) {
                dto.setCombinedPrevClose(dto.getCePrevClose().add(dto.getPePrevClose()));
            }
        }
    }

    public CombinedChartResponse getStraddleCombinedChart(String name, String expiry, BigDecimal ceStrike, BigDecimal peStrike) {
        List<StraddleIntraday> ceRows = straddleIntradayRepo.getByStrike(name, expiry, ceStrike);
        List<StraddleIntraday> peRows = straddleIntradayRepo.getByStrike(name, expiry, peStrike);
        List<StraddleIntraday> spotRows = straddleIntradayRepo.getSpotHistory(name, expiry);

        Map<String, CombinedChartPoint> map = new TreeMap<>();
        ZoneId ist = ZoneId.of("Asia/Kolkata");

        // Helper logic to populate combined data
        processChartRows(ceRows, map, ist, true, peStrike);
        processChartRows(peRows, map, ist, false, peStrike);
        processSpotRows(spotRows, map, ist, peStrike);

        for (CombinedChartPoint pt : map.values()) {
            calculateCombinedChartMetrics(pt);
        }
        CombinedChartResponse response = new CombinedChartResponse();
        response.getData().addAll(map.values());
        return response;
    }

    private void processChartRows(List<StraddleIntraday> rows, Map<String, CombinedChartPoint> map, ZoneId ist, boolean isCe, BigDecimal peStrike) {
        for (StraddleIntraday r : rows) {
            String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();
            CombinedChartPoint pt = map.computeIfAbsent(key, t -> new CombinedChartPoint(t, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, peStrike, peStrike));
            if (isCe) {
                pt.setCe(r.getCePrice());
                pt.setCeOpen(r.getCeOpenPrice());
                pt.setCeExtrinsic(r.getCeExtrinsic());
                pt.setCeIntrinsic(r.getCeIntrinsic());
                pt.setCeVwap(r.getCeVwap());
                pt.setCeIV(r.getCeIV());
                pt.setCePrevClose(r.getCePrevClose());
                pt.setCePrevLow(r.getCePrevLow());
            } else {
                pt.setPe(r.getPePrice());
                pt.setPeOpen(r.getPeOpenPrice());
                pt.setPeExtrinsic(r.getPeExtrinsic());
                pt.setPeIntrinsic(r.getPeIntrinsic());
                pt.setPeVwap(r.getPeVwap());
                pt.setPeIV(r.getPeIV());
                pt.setPePrevClose(r.getPePrevClose());
                pt.setPePrevLow(r.getPePrevLow());
            }
        }
    }

    private void processSpotRows(List<StraddleIntraday> spotRows, Map<String, CombinedChartPoint> map, ZoneId ist, BigDecimal peStrike) {
        for (StraddleIntraday r : spotRows) {
            String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();
            CombinedChartPoint pt = map.computeIfAbsent(key, t -> new CombinedChartPoint(t, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, peStrike, peStrike));
            pt.setSpot(r.getSpot());
        }
    }

    private void calculateCombinedChartMetrics(CombinedChartPoint pt) {
        if (pt.getCe() != null && pt.getPe() != null) {
            pt.setCombinedPremium(pt.getCe().add(pt.getPe()));
            pt.setAvgPrice(pt.getCombinedPremium().divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP));
        }
        if (pt.getCeVwap() != null && pt.getPeVwap() != null) pt.setCombinedVwap(pt.getCeVwap().add(pt.getPeVwap()));
        if (pt.getCeOpen() != null && pt.getPeOpen() != null) pt.setCombinedOpen(pt.getCeOpen().add(pt.getPeOpen()));
        if (pt.getCeIV() != null && pt.getPeIV() != null) pt.setCombinedIV(pt.getCeIV().add(pt.getPeIV()).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
        else if (pt.getCeIV() != null) pt.setCombinedIV(pt.getCeIV());
        else if (pt.getPeIV() != null) pt.setCombinedIV(pt.getPeIV());
        if (pt.getCePrevClose() != null && pt.getPePrevClose() != null) pt.setCombinedPrevClose(pt.getCePrevClose().add(pt.getPePrevClose()));
        if (pt.getCePrevLow() != null && pt.getPePrevLow() != null) pt.setCombinedPrevLow(pt.getCePrevLow().add(pt.getPePrevLow()));
    }
}