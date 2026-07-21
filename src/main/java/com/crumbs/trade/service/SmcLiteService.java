package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.crumbs.trade.service.FuturesStrategyService.HourlyCandle;

@Service
public class SmcLiteService {

    private static final Logger logger = LogManager.getLogger(SmcLiteService.class);

    // =========================================================
    // 🛠️ SMC PINE SCRIPT SETTINGS
    // =========================================================
    private static final int DEFAULT_SWING_LENGTH = 5; // Tuned for 1H intraday windows
    private static final int HISTORY_TO_KEEP = 20;
    private static final BigDecimal BOX_WIDTH = new BigDecimal("2.5");

    @Autowired(required = false)
    private TelegramService telegramService;

    // In-memory cache: Retains active Supply & Demand zones across scan cycles
    private final Map<String, List<SmcZone>> supplyZonesCache = new ConcurrentHashMap<>();
    private final Map<String, List<SmcZone>> demandZonesCache = new ConcurrentHashMap<>();

    // =========================================================
    // 🚀 PLUGGABLE ENTRY POINT
    // =========================================================
    /**
     * Evaluates the Pine Script SMC Lite logic on a provided candle stream.
     * 
     * @param symbol           Stock symbol (e.g., "RELIANCE")
     * @param indexType        Category (e.g., "NIFTY_50")
     * @param candles          The 1H candle array already fetched by FuturesStrategyService
     * @param ltp              Current Last Traded Price
     * @param sendNotification If true, fires Telegram alert automatically. If false, skips alert and only returns the signal.
     * @return Optional<SmcSignal> containing Break of Structure (BOS) details if triggered.
     */
    public synchronized Optional<SmcSignal> evaluateAndNotify(
            String symbol, String indexType, List<HourlyCandle> candles, BigDecimal ltp, boolean sendNotification) {

        if (candles == null || candles.size() < 15) {
            return Optional.empty();
        }

        int swingLen = Math.min(DEFAULT_SWING_LENGTH, (candles.size() - 1) / 2);
        if (swingLen < 2) return Optional.empty();

        BigDecimal atr = calculateAtr(candles);
        if (atr.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();

        List<SmcZone> supplyZones = supplyZonesCache.computeIfAbsent(symbol, k -> new ArrayList<>());
        List<SmcZone> demandZones = demandZonesCache.computeIfAbsent(symbol, k -> new ArrayList<>());

        // ──────────────────────────────────────────────────────────
        // ✅ NEW: HISTORICAL REPLAY LOOP (Scans 15 days of candles!)
        // This guarantees your zones are built even if the app just restarted.
        // ──────────────────────────────────────────────────────────
        for (int idx = swingLen; idx <= candles.size() - 1 - swingLen; idx++) {
            HourlyCandle pivotCandle = candles.get(idx);

            // Check & Build Supply Zone
            if (isPivotHigh(candles, idx, swingLen)) {
                BigDecimal boxTop = pivotCandle.high;
                BigDecimal atrBuffer = atr.multiply(BOX_WIDTH).divide(BigDecimal.TEN, 4, RoundingMode.HALF_UP);
                BigDecimal boxBottom = boxTop.subtract(atrBuffer);
                BigDecimal poi = boxTop.add(boxBottom).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);

                if (!isOverlapping(poi, supplyZones, atr)) {
                    addZoneAndPrune(supplyZones, new SmcZone("SUPPLY", boxTop, boxBottom, poi));
                }
            }

            // Check & Build Demand Zone
            if (isPivotLow(candles, idx, swingLen)) {
                BigDecimal boxBottom = pivotCandle.low;
                BigDecimal atrBuffer = atr.multiply(BOX_WIDTH).divide(BigDecimal.TEN, 4, RoundingMode.HALF_UP);
                BigDecimal boxTop = boxBottom.add(atrBuffer);
                BigDecimal poi = boxTop.add(boxBottom).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);

                if (!isOverlapping(poi, demandZones, atr)) {
                    addZoneAndPrune(demandZones, new SmcZone("DEMAND", boxTop, boxBottom, poi));
                }
            }
        }

        // 4. Evaluate Break of Structure (BOS) using the Latest Closed Bar or LTP
        HourlyCandle latestBar = candles.get(candles.size() - 1);
        BigDecimal evalPrice = (ltp != null && ltp.signum() > 0) ? ltp : latestBar.close;

        Optional<SmcSignal> signalOpt = checkBos(symbol, indexType, evalPrice, supplyZones, demandZones);

        // 5. PLUGGABLE NOTIFICATION CONTROL
        signalOpt.ifPresent(signal -> {
            logger.info("⚡ [SMC-BOS][{}] {} Triggered at ₹{} (Broken Zone: ₹{})", 
                    symbol, signal.getBosType(), signal.getCurrentPrice(), signal.getBrokenLevel());

            if (sendNotification && telegramService != null) {
                sendTelegramAlert(signal);
            } else {
                logger.info("🔕 [SMC-BOS][{}] Notification skipped by caller request.", symbol);
            }
        });

        return signalOpt;
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
                supplyIter.remove(); // Always remove broken zone so it doesn't get stuck
                
                BigDecimal diff = price.subtract(zone.getTop()).abs();
                BigDecimal maxAllowed = getMaxAllowedDifference(zone.getTop());
                
                if (diff.compareTo(maxAllowed) <= 0) {
                    return Optional.of(new SmcSignal(symbol, indexType, "BULLISH_BOS", "BUY", price, zone.getTop()));
                } else {
                    logger.info("🔕 [SMC-BOS SKIP] {} BULLISH breakout chased too far! LTP: ₹{}, BOS: ₹{}, Diff: ₹{} (Max allowed: ₹{})", 
                                symbol, price, zone.getTop(), diff, maxAllowed);
                }
            }
        }

        // Check Bearish BOS (Breaking below Demand Bottom)
        Iterator<SmcZone> demandIter = demandZones.iterator();
        while (demandIter.hasNext()) {
            SmcZone zone = demandIter.next();
            if (price.compareTo(zone.getBottom()) <= 0) {
                demandIter.remove(); // Always remove broken zone
                
                BigDecimal diff = zone.getBottom().subtract(price).abs();
                BigDecimal maxAllowed = getMaxAllowedDifference(zone.getBottom());
                
                if (diff.compareTo(maxAllowed) <= 0) {
                    return Optional.of(new SmcSignal(symbol, indexType, "BEARISH_BOS", "SELL", price, zone.getBottom()));
                } else {
                    logger.info("🔕 [SMC-BOS SKIP] {} BEARISH breakdown chased too far! LTP: ₹{}, BOS: ₹{}, Diff: ₹{} (Max allowed: ₹{})", 
                                symbol, price, zone.getBottom(), diff, maxAllowed);
                }
            }
        }

        return Optional.empty();
    }

    // ──────────────────────────────────────────────────────────
    // 🎚️ PRICE TIER MAP FOR MAX SLIPPAGE / CHASE PROTECTION
    // ──────────────────────────────────────────────────────────
    private BigDecimal getMaxAllowedDifference(BigDecimal bosLevel) {
        double level = bosLevel.doubleValue();
        
        // If stock is between 100 and 500, max diff is 10 points
        if (level <= 100)   return new BigDecimal("2.00");
        if (level <= 500)   return new BigDecimal("10.00");
        if (level <= 1000)  return new BigDecimal("15.00");
        if (level <= 2500)  return new BigDecimal("25.00");
        if (level <= 5000)  return new BigDecimal("40.00");
        if (level <= 10000) return new BigDecimal("75.00"); // Matches MCX CrudeOil nicely
        
        // For NIFTY/BANKNIFTY or stocks > 10,000
        return new BigDecimal("120.00"); 
    }

    private void sendTelegramAlert(SmcSignal sig) {
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
    // 📐 MATH & PIVOT UTILITIES (FIXED FOR EQUAL HIGHS/LOWS)
    // =========================================================
    private boolean isPivotHigh(List<HourlyCandle> candles, int targetIdx, int len) {
        if (targetIdx - len < 0 || targetIdx + len >= candles.size()) return false;
        BigDecimal targetHigh = candles.get(targetIdx).high;
        
        for (int i = targetIdx - len; i <= targetIdx + len; i++) {
            if (i == targetIdx) continue;
            // ✅ CHANGED: Using > 0 instead of >= 0 to allow double tops/equal highs!
            if (candles.get(i).high.compareTo(targetHigh) > 0) return false;
        }
        return true;
    }

    private boolean isPivotLow(List<HourlyCandle> candles, int targetIdx, int len) {
        if (targetIdx - len < 0 || targetIdx + len >= candles.size()) return false;
        BigDecimal targetLow = candles.get(targetIdx).low;
        
        for (int i = targetIdx - len; i <= targetIdx + len; i++) {
            if (i == targetIdx) continue;
            // ✅ CHANGED: Using < 0 instead of <= 0 to allow equal bottoms!
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
        zones.add(0, newZone); // array.unshift
        while (zones.size() > HISTORY_TO_KEEP) {
            zones.remove(zones.size() - 1); // array.pop
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