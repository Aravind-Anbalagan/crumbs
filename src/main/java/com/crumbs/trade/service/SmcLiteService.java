package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.FuturesBreakEvent;
import com.crumbs.trade.service.FuturesStrategyService.HourlyCandle;

@Service
public class SmcLiteService {

    private static final Logger logger = LogManager.getLogger(SmcLiteService.class);

    // =========================================================
    // 🛠️ SMC PINE SCRIPT & TRADE SETTINGS
    // =========================================================
    private static final int DEFAULT_SWING_LENGTH = 5; // Tuned for 1H intraday windows
    private static final int HISTORY_TO_KEEP = 20;
    private static final BigDecimal BOX_WIDTH = new BigDecimal("2.5");

    // 🎚️ 1% Max Chase Percentage (Easily configurable: 0.01 = 1%, 0.005 = 0.5%)
    private static final BigDecimal MAX_CHASE_PERCENT = new BigDecimal("0.01");

    @Autowired(required = false)
    private TelegramService telegramService;

    // In-memory caches and concurrency locks
    private final Map<String, List<SmcZone>> supplyZonesCache = new ConcurrentHashMap<>();
    private final Map<String, List<SmcZone>> demandZonesCache = new ConcurrentHashMap<>();
    private final Map<String, Object> symbolLocks = new ConcurrentHashMap<>(); // Per-symbol lock

    /**
     * ✅ THREAD-SAFE: Uses per-symbol locking to allow concurrent multi-symbol scanning.
     */
    public Optional<FuturesBreakEvent> evaluateAndNotify(String name, String indexType,
                                                         List<HourlyCandle> candles,
                                                         BigDecimal ltp, boolean canNotify) {

        if (candles == null || candles.size() < (DEFAULT_SWING_LENGTH * 2 + 1)) {
            return Optional.empty();
        }

        // 🔒 Lock only the specific symbol being evaluated so other threads aren't blocked
        Object symbolLock = symbolLocks.computeIfAbsent(name, k -> new Object());

        synchronized (symbolLock) {
            int size = candles.size();
            BigDecimal atr = calculateAtr(candles);

            List<SmcZone> supplyZones = supplyZonesCache.computeIfAbsent(name, k -> new ArrayList<>());
            List<SmcZone> demandZones = demandZonesCache.computeIfAbsent(name, k -> new ArrayList<>());

            // 1. 🏗️ BUILD STRUCTURE: Scan historical candles to establish active Supply/Demand zones
            for (int i = DEFAULT_SWING_LENGTH; i < size - DEFAULT_SWING_LENGTH; i++) {
                HourlyCandle bar = candles.get(i);

                // Detect Pivot High -> Create Supply Zone
                if (isPivotHigh(candles, i, DEFAULT_SWING_LENGTH)) {
                    BigDecimal top = bar.high;
                    BigDecimal bottom = top.subtract(atr.multiply(BOX_WIDTH));
                    if (!isOverlapping(top, supplyZones, atr)) {
                        addZoneAndPrune(supplyZones, new SmcZone("SUPPLY", top, bottom, top));
                    }
                }

                // Detect Pivot Low -> Create Demand Zone
                if (isPivotLow(candles, i, DEFAULT_SWING_LENGTH)) {
                    BigDecimal bottom = bar.low;
                    BigDecimal top = bottom.add(atr.multiply(BOX_WIDTH));
                    if (!isOverlapping(bottom, demandZones, atr)) {
                        addZoneAndPrune(demandZones, new SmcZone("DEMAND", top, bottom, bottom));
                    }
                }
            }

            // 2. 💥 CHECK BOS & RECENCY: Evaluate if live LTP or latest closed bar broke an active zone
            HourlyCandle latestBar = candles.get(size - 1);
            BigDecimal evalPrice = (ltp != null && ltp.compareTo(BigDecimal.ZERO) > 0) ? ltp : latestBar.close;

            Optional<SmcSignal> bosSignal = checkBos(name, indexType, evalPrice, supplyZones, demandZones);

            // 3. 📤 DISPATCH ALERT & RETURN EVENT
            if (bosSignal.isPresent() && canNotify) {
                SmcSignal sig = bosSignal.get();
                logger.info("🔒 [SMC BOS CONFIRMED] {} {} at ₹{} (Broken Level: ₹{})",
                        name, sig.getBosType(), evalPrice, sig.getBrokenLevel());

                sendTelegramAlert(sig);
                return Optional.of(mapToBreakEvent(name, indexType, sig, evalPrice));
            }

            return Optional.empty();
        }
    }

    // =========================================================
    // 💥 BREAK OF STRUCTURE (BOS) EVALUATION
    // =========================================================
    private Optional<SmcSignal> checkBos(String symbol, String indexType, BigDecimal price,
                                         List<SmcZone> supplyZones, List<SmcZone> demandZones) {

        // Check Bullish BOS (Breaking above Supply Top)
        Iterator<SmcZone> supplyIter = supplyZones.iterator();
        while (supplyIter.hasNext()) {
            SmcZone zone = supplyIter.next();
            if (price.compareTo(zone.getTop()) >= 0) {
                supplyIter.remove();

                BigDecimal diff = price.subtract(zone.getTop()).abs();
                BigDecimal maxAllowed = getMaxAllowedDifference(zone.getTop());

                if (diff.compareTo(maxAllowed) <= 0) {
                    return Optional.of(new SmcSignal(symbol, indexType, "BULLISH_BOS", "BUY", price, zone.getTop()));
                } else {
                    logger.info("🔕 [SMC-BOS SKIP] {} BULLISH breakout chased too far! LTP: ₹{}, BOS: ₹{}, Diff: ₹{} (Max: ₹{})",
                            symbol, price, zone.getTop(), diff, maxAllowed);
                }
            }
        }

        // Check Bearish BOS (Breaking below Demand Bottom)
        Iterator<SmcZone> demandIter = demandZones.iterator();
        while (demandIter.hasNext()) {
            SmcZone zone = demandIter.next();
            if (price.compareTo(zone.getBottom()) <= 0) {
                demandIter.remove();

                BigDecimal diff = zone.getBottom().subtract(price).abs();
                BigDecimal maxAllowed = getMaxAllowedDifference(zone.getBottom());

                if (diff.compareTo(maxAllowed) <= 0) {
                    return Optional.of(new SmcSignal(symbol, indexType, "BEARISH_BOS", "SELL", price, zone.getBottom()));
                } else {
                    logger.info("🔕 [SMC-BOS SKIP] {} BEARISH breakdown chased too far! LTP: ₹{}, BOS: ₹{}, Diff: ₹{} (Max: ₹{})",
                            symbol, price, zone.getBottom(), diff, maxAllowed);
                }
            }
        }

        return Optional.empty();
    }

    // ──────────────────────────────────────────────────────────
    // 🎚️ DYNAMIC MAX SLIPPAGE / CHASE PROTECTION
    // ──────────────────────────────────────────────────────────
    private BigDecimal getMaxAllowedDifference(BigDecimal bosLevel) {
        // Automatically calculates max slippage using the configurable percentage
        return bosLevel.multiply(MAX_CHASE_PERCENT).setScale(2, RoundingMode.HALF_UP);
    }

    private FuturesBreakEvent mapToBreakEvent(String name, String indexType, SmcSignal sig, BigDecimal ltp) {
        FuturesBreakEvent event = new FuturesBreakEvent();
        event.setName(name);
        event.setIndexType(indexType);
        event.setBreakType("BUY".equals(sig.getDirection()) ? "BREAKOUT" : "BREAKDOWN");
        event.setBreakPrice(sig.getBrokenLevel());
        event.setCurrentPrice(ltp);
        event.setReferenceLevel(sig.getBrokenLevel());
        event.setStopLoss(sig.getBrokenLevel());
        event.setStatus("ACTIVE");
        event.setBreakTime(LocalDateTime.now());
        return event;
    }

    private void sendTelegramAlert(SmcSignal sig) {
        if (telegramService == null) return;
        String emoji = "BUY".equals(sig.getDirection()) ? "🚀" : "📉";
        String message = String.format("""
            %s *SMC LITE - BREAK OF STRUCTURE* %s
            
            📌 *Stock* : `%s` (%s)
            ⚡ *Signal* : `%s` (%s)
            💥 *BOS Level* : ₹%.2f
            💰 *Current LTP* : ₹%.2f
            ⏰ *Time* : %s
            """,
                emoji, emoji, sig.getSymbol(), sig.getIndexType(),
                sig.getDirection(), sig.getBosType(), sig.getBrokenLevel(),
                sig.getCurrentPrice(), LocalDateTime.now(ZoneId.of("Asia/Kolkata")));

        try {
            telegramService.sendBroadcast(message);
        } catch (Exception e) {
            logger.error("Failed sending SMC Telegram alert for {}: {}", sig.getSymbol(), e.getMessage());
        }
    }

    // =========================================================
    // 📐 MATH & PIVOT UTILITIES
    // =========================================================
    private boolean isPivotHigh(List<HourlyCandle> candles, int targetIdx, int len) {
        if (targetIdx - len < 0 || targetIdx + len >= candles.size()) return false;
        BigDecimal targetHigh = candles.get(targetIdx).high;
        for (int i = targetIdx - len; i <= targetIdx + len; i++) {
            if (i == targetIdx) continue;
            if (candles.get(i).high.compareTo(targetHigh) > 0) return false;
        }
        return true;
    }

    private boolean isPivotLow(List<HourlyCandle> candles, int targetIdx, int len) {
        if (targetIdx - len < 0 || targetIdx + len >= candles.size()) return false;
        BigDecimal targetLow = candles.get(targetIdx).low;
        for (int i = targetIdx - len; i <= targetIdx + len; i++) {
            if (i == targetIdx) continue;
            if (candles.get(i).low.compareTo(targetLow) < 0) return false;
        }
        return true;
    }

    private boolean isOverlapping(BigDecimal newPoi, List<SmcZone> existingZones, BigDecimal atr) {
        BigDecimal threshold = atr.multiply(BigDecimal.valueOf(2));
        for (SmcZone zone : existingZones) {
            if (newPoi.compareTo(zone.getPoi().subtract(threshold)) >= 0 &&
                    newPoi.compareTo(zone.getPoi().add(threshold)) <= 0) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal calculateAtr(List<HourlyCandle> candles) {
        if (candles.size() < 2) return BigDecimal.ZERO;
        BigDecimal trSum = BigDecimal.ZERO;
        for (int i = 1; i < candles.size(); i++) {
            HourlyCandle curr = candles.get(i);
            HourlyCandle prev = candles.get(i - 1);
            BigDecimal hl = curr.getRange();
            BigDecimal hc = curr.high.subtract(prev.close).abs();
            BigDecimal lc = curr.low.subtract(prev.close).abs();
            trSum = trSum.add(hl.max(hc).max(lc));
        }
        return trSum.divide(BigDecimal.valueOf(candles.size() - 1), 4, RoundingMode.HALF_UP);
    }

    private void addZoneAndPrune(List<SmcZone> zones, SmcZone newZone) {
        zones.add(0, newZone);
        while (zones.size() > HISTORY_TO_KEEP) {
            zones.remove(zones.size() - 1);
        }
    }

    // =========================================================
    // 📦 DATA CLASSES (ZONES & SIGNALS)
    // =========================================================
    public static class SmcZone {
        private final String type;
        private final BigDecimal top;
        private final BigDecimal bottom;
        private final BigDecimal poi;

        public SmcZone(String type, BigDecimal top, BigDecimal bottom, BigDecimal poi) {
            this.type = type; this.top = top; this.bottom = bottom; this.poi = poi;
        }
        public BigDecimal getTop() { return top; }
        public BigDecimal getBottom() { return bottom; }
        public BigDecimal getPoi() { return poi; }
    }

    public static class SmcSignal {
        private final String symbol;
        private final String indexType;
        private final String bosType;
        private final String direction;
        private final BigDecimal currentPrice;
        private final BigDecimal brokenLevel;

        public SmcSignal(String symbol, String indexType, String bosType, String direction, BigDecimal currentPrice, BigDecimal brokenLevel) {
            this.symbol = symbol; this.indexType = indexType; this.bosType = bosType;
            this.direction = direction; this.currentPrice = currentPrice; this.brokenLevel = brokenLevel;
        }
        public String getSymbol() { return symbol; }
        public String getIndexType() { return indexType; }
        public String getBosType() { return bosType; }
        public String getDirection() { return direction; }
        public BigDecimal getCurrentPrice() { return currentPrice; }
        public BigDecimal getBrokenLevel() { return brokenLevel; }
    }
}