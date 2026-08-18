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
    private final Map<String, BosRecord> recentBosCache = new ConcurrentHashMap<>();
    // =========================================================
    // 🛠️ SMC PINE SCRIPT & TRADE SETTINGS
    // =========================================================
    private static final int DEFAULT_SWING_LENGTH = 5; // Tuned for 1H intraday windows
    private static final int HISTORY_TO_KEEP = 20;
    private static final BigDecimal BOX_WIDTH = new BigDecimal("2.5");
    private final Map<String, LocalDateTime> lastNotifiedAt = new ConcurrentHashMap<>();
    private static final long NOTIFY_COOLDOWN_MINUTES = 60;
    // 🎚️ 1% Max Chase Percentage (Easily configurable: 0.01 = 1%, 0.005 = 0.5%)
    private static final BigDecimal MAX_CHASE_PERCENT = new BigDecimal("0.01");

    @Autowired(required = false)
    private TelegramService telegramService;

    // In-memory caches and concurrency locks
    private final Map<String, List<SmcZone>> supplyZonesCache = new ConcurrentHashMap<>();
    private final Map<String, List<SmcZone>> demandZonesCache = new ConcurrentHashMap<>();
    private final Map<String, Object> symbolLocks = new ConcurrentHashMap<>(); // Per-symbol lock
    // Add near the top-level config constants
    private static final BigDecimal NEAR_ZONE_PERCENT = new BigDecimal("0.5"); // within 0.5% of a zone edge = "near"
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    public enum ZoneStatus {
        NEAR_SUPPORT,
        NEAR_RESISTANCE,
        INSIDE_ZONE,
        NO_NEARBY_ZONE,   // zones exist for this symbol, but price isn't close to one
        NO_ZONE_DATA      // this symbol hasn't been SMC-evaluated yet
    }
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
            if (bosSignal.isPresent()) {
                SmcSignal sig = bosSignal.get();
                recentBosCache.put(name, new BosRecord(sig.getDirection(), sig.getBrokenLevel(), LocalDateTime.now()));

                if (canNotify) {
                    logger.info("🔒 [SMC BOS CONFIRMED] {} {} at ₹{} (Broken Level: ₹{})",
                            name, sig.getBosType(), evalPrice, sig.getBrokenLevel());

                    sendTelegramAlertThrottled(sig);
                    return Optional.of(mapToBreakEvent(name, indexType, sig, evalPrice));
                }
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
    public static class ZoneInfo {
        private final ZoneStatus status;
        private final BigDecimal level;       // the S/R price level in question
        private final BigDecimal distancePct; // distance from LTP to that level, in %

        public ZoneInfo(ZoneStatus status, BigDecimal level, BigDecimal distancePct) {
            this.status = status; this.level = level; this.distancePct = distancePct;
        }
        public ZoneStatus getStatus() { return status; }
        public BigDecimal getLevel() { return level; }
        public BigDecimal getDistancePct() { return distancePct; }
    }

    /**
     * Read-only lookup for FnoScannerService: does this symbol's LTP sit near a known
     * Supply (resistance) or Demand (support) zone, or is it moving through open space?
     *
     * Does NOT build zones itself — it reads whatever the hourly SMC evaluation cycle
     * has already cached for this symbol. If that symbol hasn't gone through
     * evaluateAndNotify() yet, this returns NO_ZONE_DATA (see note below on keeping
     * caches warm).
     */
    public ZoneInfo getZoneProximity(String name, BigDecimal ltp, String moveDirection) {
        if (ltp == null || ltp.compareTo(BigDecimal.ZERO) <= 0) {
            return new ZoneInfo(ZoneStatus.NO_ZONE_DATA, null, null);
        }

        Object symbolLock = symbolLocks.computeIfAbsent(name, k -> new Object());
        synchronized (symbolLock) {
            List<SmcZone> supplyZones = supplyZonesCache.getOrDefault(name, List.of());
            List<SmcZone> demandZones = demandZonesCache.getOrDefault(name, List.of());

            if (supplyZones.isEmpty() && demandZones.isEmpty()) {
                return new ZoneInfo(ZoneStatus.NO_ZONE_DATA, null, null);
            }

            // Check the zone in the direction of the current move first — that's the
            // one that actually decides whether a straddle is safe right now.
            boolean checkResistanceFirst = "GAIN".equalsIgnoreCase(moveDirection);

            ZoneInfo primary = checkResistanceFirst
                    ? evaluateResistance(supplyZones, ltp)
                    : evaluateSupport(demandZones, ltp);

            if (primary.getStatus() != ZoneStatus.NO_NEARBY_ZONE) {
                return primary;
            }

            // Fallback: check the opposite side in case price already reversed
            return checkResistanceFirst
                    ? evaluateSupport(demandZones, ltp)
                    : evaluateResistance(supplyZones, ltp);
        }
    }

    /**
     * Read-only lookup for FnoScannerService: was a BOS confirmed for this symbol
     * recently? Only returns same-trading-day records — a break from yesterday
     * isn't useful context for a live intraday scanner.
     */
    public Optional<BosRecord> getRecentBos(String name) {
        BosRecord rec = recentBosCache.get(name);
        if (rec == null) return Optional.empty();
        if (!rec.getConfirmedAt().toLocalDate().equals(LocalDateTime.now().toLocalDate())) {
            return Optional.empty(); // stale — from a prior day, drop it
        }
        return Optional.of(rec);
    }
    private ZoneInfo evaluateResistance(List<SmcZone> supplyZones, BigDecimal ltp) {
        SmcZone nearest = findNearestZoneAbove(supplyZones, ltp);
        if (nearest == null) return new ZoneInfo(ZoneStatus.NO_NEARBY_ZONE, null, null);

        if (ltp.compareTo(nearest.getBottom()) >= 0 && ltp.compareTo(nearest.getTop()) <= 0) {
            return new ZoneInfo(ZoneStatus.INSIDE_ZONE, nearest.getTop(), BigDecimal.ZERO);
        }

        BigDecimal distancePct = nearest.getBottom().subtract(ltp)
                .divide(ltp, 4, RoundingMode.HALF_UP).multiply(HUNDRED);

        return distancePct.compareTo(NEAR_ZONE_PERCENT) <= 0
                ? new ZoneInfo(ZoneStatus.NEAR_RESISTANCE, nearest.getTop(), distancePct)
                : new ZoneInfo(ZoneStatus.NO_NEARBY_ZONE, nearest.getTop(), distancePct);
    }

    private ZoneInfo evaluateSupport(List<SmcZone> demandZones, BigDecimal ltp) {
        SmcZone nearest = findNearestZoneBelow(demandZones, ltp);
        if (nearest == null) return new ZoneInfo(ZoneStatus.NO_NEARBY_ZONE, null, null);

        if (ltp.compareTo(nearest.getBottom()) >= 0 && ltp.compareTo(nearest.getTop()) <= 0) {
            return new ZoneInfo(ZoneStatus.INSIDE_ZONE, nearest.getBottom(), BigDecimal.ZERO);
        }

        BigDecimal distancePct = ltp.subtract(nearest.getTop())
                .divide(ltp, 4, RoundingMode.HALF_UP).multiply(HUNDRED);

        return distancePct.compareTo(NEAR_ZONE_PERCENT) <= 0
                ? new ZoneInfo(ZoneStatus.NEAR_SUPPORT, nearest.getBottom(), distancePct)
                : new ZoneInfo(ZoneStatus.NO_NEARBY_ZONE, nearest.getBottom(), distancePct);
    }

    private SmcZone findNearestZoneAbove(List<SmcZone> zones, BigDecimal price) {
        SmcZone nearest = null;
        for (SmcZone z : zones) {
            if (z.getTop().compareTo(price) < 0) continue; // fully below price, irrelevant
            if (nearest == null || z.getBottom().compareTo(nearest.getBottom()) < 0) nearest = z;
        }
        return nearest;
    }

    private SmcZone findNearestZoneBelow(List<SmcZone> zones, BigDecimal price) {
        SmcZone nearest = null;
        for (SmcZone z : zones) {
            if (z.getBottom().compareTo(price) > 0) continue; // fully above price, irrelevant
            if (nearest == null || z.getTop().compareTo(nearest.getTop()) > 0) nearest = z;
        }
        return nearest;
    }

    /**
     * BOS-only recheck: mutates the cache (removes broken zones) but does NOT
     * rebuild zones and does NOT fetch candles. Safe to call every 15 min.
     */
    public Optional<FuturesBreakEvent> recheckBosOnly(String name, String indexType,
                                                      BigDecimal ltp, boolean canNotify) {
        if (ltp == null || ltp.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }

        Object symbolLock = symbolLocks.computeIfAbsent(name, k -> new Object());
        synchronized (symbolLock) {
            List<SmcZone> supplyZones = supplyZonesCache.get(name);
            List<SmcZone> demandZones = demandZonesCache.get(name);

            if ((supplyZones == null || supplyZones.isEmpty()) &&
                    (demandZones == null || demandZones.isEmpty())) {
                return Optional.empty();
            }

            Optional<SmcSignal> bosSignal = checkBos(name, indexType, ltp,
                    supplyZones != null ? supplyZones : List.of(),
                    demandZones != null ? demandZones : List.of());

            if (bosSignal.isPresent()) {
                SmcSignal sig = bosSignal.get();
                recentBosCache.put(name, new BosRecord(sig.getDirection(), sig.getBrokenLevel(), LocalDateTime.now()));

                if (canNotify) {
                    logger.info("🔒 [SMC BOS - 15min recheck] {} {} at ₹{}", name, sig.getBosType(), ltp);
                    sendTelegramAlertThrottled(sig);
                    return Optional.of(mapToBreakEvent(name, indexType, sig, ltp));
                }
            }
            return Optional.empty();
        }
    }


    private boolean sendTelegramAlertThrottled(SmcSignal sig) {
        String key = sig.getSymbol() + "_" + sig.getBosType();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastSent = lastNotifiedAt.get(key);

        if (lastSent != null && lastSent.plusMinutes(NOTIFY_COOLDOWN_MINUTES).isAfter(now)) {
            logger.info("🔕 [SMC-BOS THROTTLED] {} {} — already notified at {} (cooldown active)",
                    sig.getSymbol(), sig.getBosType(), lastSent);
            return false;
        }

        sendTelegramAlert(sig);
        lastNotifiedAt.put(key, now);
        return true;
    }
    // Tracks the most recent confirmed BOS per symbol, so FnoScannerService can
// check "was there already a confirmed break here?" without touching the
// FuturesBreakEvent repo. Refreshed every time evaluateAndNotify() or
// recheckBosOnly() confirms a break — at least every 15 min during market
// hours, so it's never more than one recheck cycle stale.


    public static class BosRecord {
        private final String direction;       // "BUY" or "SELL"
        private final BigDecimal brokenLevel;
        private final LocalDateTime confirmedAt;

        public BosRecord(String direction, BigDecimal brokenLevel, LocalDateTime confirmedAt) {
            this.direction = direction;
            this.brokenLevel = brokenLevel;
            this.confirmedAt = confirmedAt;
        }
        public String getDirection() { return direction; }
        public BigDecimal getBrokenLevel() { return brokenLevel; }
        public LocalDateTime getConfirmedAt() { return confirmedAt; }
    }
}