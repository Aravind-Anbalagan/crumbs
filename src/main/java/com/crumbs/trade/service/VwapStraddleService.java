package com.crumbs.trade.service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.VwapStraddleAddRequest;
import com.crumbs.trade.dto.VwapStraddleTickDto;
import com.crumbs.trade.dto.VwapStraddleUpdateRequest;
import com.crumbs.trade.dto.VwapStraddleWatchlistDto;
import com.crumbs.trade.entity.VwapStraddleWatchlist;
import com.crumbs.trade.repo.VwapStraddleWatchlistRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class VwapStraddleService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String EXCHANGE_NFO = "NFO";
    private static final int BATCH_SIZE = 50;
    private static final long BATCH_DELAY_MS = 250L;
    private static final BigDecimal STRIKE_MULTIPLIER = new BigDecimal("100");

    private static final DateTimeFormatter INDEX_EXPIRY_FMT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("ddMMMyyyy")
            .toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DISPLAY_EXPIRY_FMT = DateTimeFormatter.ofPattern("dd-MMM", Locale.ENGLISH);
    private static final DateTimeFormatter ANGEL_CANDLE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final VwapStraddleWatchlistRepo watchlistRepo;
    private final AngelOne angelOne;
    private final PredictionService predictionService;
    private final TelegramService telegramService;

    // In-memory 1-minute chart & VWAP accumulator cache (keyed by watchlist ID)
    private final Map<Long, SeriesBuffer> seriesCache = new ConcurrentHashMap<>();

    private record QuoteData(BigDecimal ltp, Long cumVolume) {}
    private record CandleBar(BigDecimal close, long volume) {}

    private static class SeriesBuffer {
        BigDecimal cumPv = BigDecimal.ZERO;
        long cumVol = 0L;
        long lastEpochSeconds = -1L;
        Long lastCeCumVol = null;
        Long lastPeCumVol = null;
        volatile boolean backfilled = false;
        final List<VwapStraddleTickDto> ticks = new CopyOnWriteArrayList<>();

        synchronized BigDecimal previewNextCv(BigDecimal cp, long barVol) {
            long vol = Math.max(1L, barVol);
            BigDecimal nextPv = cumPv.add(cp.multiply(BigDecimal.valueOf(vol)));
            long nextVol = cumVol + vol;
            return nextPv.divide(BigDecimal.valueOf(nextVol), 2, RoundingMode.HALF_UP);
        }

        synchronized VwapStraddleTickDto append(LocalDateTime ts, BigDecimal cp, long barVol, String signalEvent) {
            long epochSeconds = ts.atZone(IST).toEpochSecond();
            if (epochSeconds <= lastEpochSeconds) {
                return latest(); // Prevent duplicate minute from backfill/live overlap
            }
            long vol = Math.max(1L, barVol);
            cumPv = cumPv.add(cp.multiply(BigDecimal.valueOf(vol)));
            cumVol += vol;
            lastEpochSeconds = epochSeconds;

            BigDecimal cv = cumPv.divide(BigDecimal.valueOf(cumVol), 2, RoundingMode.HALF_UP);
            VwapStraddleTickDto tick = new VwapStraddleTickDto(
                    ts.format(TIME_FMT), epochSeconds, cp, cv, signalEvent);
            ticks.add(tick);
            return tick;
        }

        synchronized long computeLiveBarVolume(Long ceCumVol, Long peCumVol) {
            if (ceCumVol == null || peCumVol == null) {
                return 1L; // Fallback if bulk quote is LTP-only mode
            }
            long ceDelta = (lastCeCumVol != null && ceCumVol >= lastCeCumVol) ? (ceCumVol - lastCeCumVol) : 0L;
            long peDelta = (lastPeCumVol != null && peCumVol >= lastPeCumVol) ? (peCumVol - lastPeCumVol) : 0L;
            lastCeCumVol = ceCumVol;
            lastPeCumVol = peCumVol;
            return Math.max(1L, ceDelta + peDelta);
        }

        VwapStraddleTickDto latest() {
            return ticks.isEmpty() ? null : ticks.get(ticks.size() - 1);
        }
    }

    // ──────────────────────────────────────────────────────────
    //  CACHE RESET
    // ──────────────────────────────────────────────────────────

    public void clearDailyCache() {
        seriesCache.clear();
        log.info("Cleared VWAP Straddle in-memory tick cache for new trading day.");
    }

    // ──────────────────────────────────────────────────────────
    //  1-MINUTE SCHEDULER CORE LOOP
    // ──────────────────────────────────────────────────────────

    public void processOneMinuteTick() {
        LocalDate today = LocalDate.now(IST);
        List<VwapStraddleWatchlist> activeList =
                watchlistRepo.findByTradeDateAndIsActiveTrueOrderByIdAsc(today);

        if (activeList.isEmpty()) {
            return;
        }

        SmartConnect smartConnect = angelOne.signIn();
        if (smartConnect == null) {
            log.error("VWAP Straddle: AngelOne sign-in failed.");
            return;
        }

        // Seed today's 09:15 AM -> last completed minute once if cache is empty
        for (VwapStraddleWatchlist item : activeList) {
            backfillIfNeeded(item, smartConnect);
        }

        List<String> allTokens = activeList.stream()
                .flatMap(w -> java.util.stream.Stream.of(w.getCeToken(), w.getPeToken()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<String, QuoteData> quoteMap = fetchBatchQuotes(smartConnect, allTokens);
        if (quoteMap.isEmpty()) {
            log.warn("VWAP Straddle: no quotes returned for {} tokens.", allTokens.size());
            return;
        }

        LocalDateTime now = LocalDateTime.now(IST).withSecond(0).withNano(0);
        List<VwapStraddleWatchlist> dirtyRows = new ArrayList<>();

        for (VwapStraddleWatchlist item : activeList) {
            QuoteData ceQuote = quoteMap.get(item.getCeToken());
            QuoteData peQuote = quoteMap.get(item.getPeToken());

            if (ceQuote == null || peQuote == null
                    || ceQuote.ltp() == null || peQuote.ltp() == null
                    || ceQuote.ltp().compareTo(BigDecimal.ZERO) <= 0
                    || peQuote.ltp().compareTo(BigDecimal.ZERO) <= 0) {
                log.debug("Skipping {} {} — missing leg LTP (CE={}, PE={})",
                        item.getSymbol(), item.getStrike(), ceQuote, peQuote);
                continue;
            }

            BigDecimal cp = ceQuote.ltp().add(peQuote.ltp()).setScale(2, RoundingMode.HALF_UP);
            SeriesBuffer buffer = seriesCache.computeIfAbsent(item.getId(), k -> new SeriesBuffer());
            long barVol = buffer.computeLiveBarVolume(ceQuote.cumVolume(), peQuote.cumVolume());
            BigDecimal cv = buffer.previewNextCv(cp, barVol);

            String prevStatus = item.getStatus();
            int prevHits = item.getCurrentHits();
            String signalEvent = evaluateStateMachine(item, cp, cv);

            buffer.append(now, cp, barVol, signalEvent);

            if (!Objects.equals(prevStatus, item.getStatus()) || prevHits != item.getCurrentHits()) {
                dirtyRows.add(item);
            }

            if (signalEvent != null && "Y".equalsIgnoreCase(item.getAlertFlag())) {
                sendTelegramAlert(item, signalEvent, cp, cv, now);
            }
        }

        if (!dirtyRows.isEmpty()) {
            watchlistRepo.saveAll(dirtyRows);
        }
    }

    private String evaluateStateMachine(VwapStraddleWatchlist item, BigDecimal cp, BigDecimal cv) {
        if ("WAITING".equalsIgnoreCase(item.getStatus())) {
            if (cp.compareTo(cv) < 0) {
                int hits = item.getCurrentHits() + 1;
                if (hits >= item.getEntryHitTarget()) {
                    item.setStatus("IN_POSITION");
                    item.setEntryCp(cp);
                    item.setCurrentHits(0);
                    log.info("ENTRY triggered: {} {} | CP={} CV={} | Hits={}/{}",
                            item.getSymbol(), item.getStrike(), cp, cv, hits, item.getEntryHitTarget());
                    return "ENTRY";
                }
                item.setCurrentHits(hits);
            } else {
                item.setCurrentHits(0);
            }
        } else if ("IN_POSITION".equalsIgnoreCase(item.getStatus())) {
            if (cp.compareTo(cv) > 0) {
                int hits = item.getCurrentHits() + 1;
                if (hits >= item.getExitHitTarget()) {
                    item.setStatus("WAITING");
                    item.setEntryCp(null);
                    item.setCurrentHits(0);
                    log.info("EXIT triggered: {} {} | CP={} CV={} | Hits={}/{}",
                            item.getSymbol(), item.getStrike(), cp, cv, hits, item.getExitHitTarget());
                    return "EXIT";
                }
                item.setCurrentHits(hits);
            } else {
                item.setCurrentHits(0);
            }
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────
    //  ONE-TIME INTRADAY HISTORICAL BACKFILL (09:15 AM -> NOW-1m)
    // ──────────────────────────────────────────────────────────

    private void backfillIfNeeded(VwapStraddleWatchlist item, SmartConnect smartConnect) {
        SeriesBuffer buffer = seriesCache.computeIfAbsent(item.getId(), k -> new SeriesBuffer());
        synchronized (buffer) {
            if (buffer.backfilled) {
                return;
            }

            LocalDateTime now = LocalDateTime.now(IST);
            LocalDateTime marketOpen = now.toLocalDate().atTime(9, 15);
            if (now.isBefore(marketOpen.plusMinutes(1))) {
                buffer.backfilled = true;
                return;
            }

            if (smartConnect == null) {
                smartConnect = angelOne.signIn();
            }
            if (smartConnect == null) {
                return;
            }

            String fromDate = marketOpen.format(ANGEL_CANDLE_FMT);
            String toDate = now.minusMinutes(1).format(ANGEL_CANDLE_FMT);

            Map<LocalDateTime, CandleBar> ceBars = fetchOneMinCandles(smartConnect, item.getCeToken(), fromDate, toDate);
            sleepQuietly(BATCH_DELAY_MS);
            Map<LocalDateTime, CandleBar> peBars = fetchOneMinCandles(smartConnect, item.getPeToken(), fromDate, toDate);

            long ceTotalVol = 0L;
            long peTotalVol = 0L;
            int added = 0;

            for (Map.Entry<LocalDateTime, CandleBar> entry : ceBars.entrySet()) {
                LocalDateTime ts = entry.getKey();
                CandleBar ce = entry.getValue();
                CandleBar pe = peBars.get(ts);
                if (pe == null) continue;

                BigDecimal cp = ce.close().add(pe.close()).setScale(2, RoundingMode.HALF_UP);
                long barVol = Math.max(1L, ce.volume() + pe.volume());
                ceTotalVol += ce.volume();
                peTotalVol += pe.volume();

                // Seed memory only (signalEvent = null, no historical Telegram alerts)
                buffer.append(ts, cp, barVol, null);
                added++;
            }

            if (added > 0) {
                buffer.lastCeCumVol = ceTotalVol;
                buffer.lastPeCumVol = peTotalVol;
                buffer.backfilled = true;
                log.info("VWAP Straddle backfilled {} candles for {} {}",
                        added, item.getSymbol(), item.getStrike());
            } else {
                log.warn("VWAP Straddle backfill returned 0 matched candles for {} {} (will retry next tick)",
                        item.getSymbol(), item.getStrike());
            }
        }
    }

    private Map<LocalDateTime, CandleBar> fetchOneMinCandles(
            SmartConnect smartConnect, String token, String fromDate, String toDate) {
        Map<LocalDateTime, CandleBar> bars = new TreeMap<>();
        try {
            JSONObject req = new JSONObject();
            req.put("exchange", EXCHANGE_NFO);
            req.put("symboltoken", token);
            req.put("interval", "ONE_MINUTE");
            req.put("fromdate", fromDate);
            req.put("todate", toDate);

            JSONArray candles = smartConnect.candleData(req);
            if (candles == null) return bars;

            for (int i = 0; i < candles.length(); i++) {
                JSONArray row = candles.getJSONArray(i);
                LocalDateTime ts = OffsetDateTime.parse(row.getString(0))
                        .atZoneSameInstant(IST)
                        .toLocalDateTime()
                        .withSecond(0)
                        .withNano(0);
                BigDecimal close = new BigDecimal(row.get(4).toString());
                long volume = row.getLong(5);
                bars.put(ts, new CandleBar(close, volume));
            }
        } catch (Exception e) {
            log.error("VWAP Straddle backfill error for token {}: {}", token, e.getMessage());
        }
        return bars;
    }

    // ──────────────────────────────────────────────────────────
    //  BATCH QUOTE FETCH (Reuses PredictionService helpers)
    // ──────────────────────────────────────────────────────────

    private Map<String, QuoteData> fetchBatchQuotes(SmartConnect smartConnect, List<String> tokens) {
        Map<String, QuoteData> quoteMap = new HashMap<>();

        for (int i = 0; i < tokens.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, tokens.size());
            List<String> batch = tokens.subList(i, end);

            try {
                JSONObject payload = predictionService.buildMarketDataPayload(batch, EXCHANGE_NFO);
                JSONObject response = predictionService.callMarketDataWithRetry(smartConnect, payload);
                JSONArray fetched = extractFetchedArray(response);

                if (fetched != null) {
                    for (int j = 0; j < fetched.length(); j++) {
                        JSONObject obj = fetched.getJSONObject(j);
                        String token = obj.get("symbolToken").toString();
                        if (obj.has("ltp") && !obj.isNull("ltp")) {
                            BigDecimal ltp = new BigDecimal(obj.get("ltp").toString());
                            Long cumVol = (obj.has("tradeVolume") && !obj.isNull("tradeVolume"))
                                    ? obj.getLong("tradeVolume")
                                    : null;
                            quoteMap.put(token, new QuoteData(ltp, cumVol));
                        }
                    }
                }
            } catch (SmartAPIException e) {
                log.error("VWAP Straddle: SmartAPI batch quote error at offset {}: {}", i, e.getMessage());
            } catch (Exception e) {
                log.error("VWAP Straddle: batch quote error at offset {}: {}", i, e.getMessage());
            }

            if (end < tokens.size()) {
                sleepQuietly(BATCH_DELAY_MS);
            }
        }
        return quoteMap;
    }

    private JSONArray extractFetchedArray(JSONObject response) {
        if (response == null) return null;
        try {
            if (response.has("data") && !response.isNull("data")) {
                JSONObject data = response.getJSONObject("data");
                if (data.has("fetched")) return data.getJSONArray("fetched");
            }
            if (response.has("fetched")) return response.getJSONArray("fetched");
        } catch (JSONException e) {
            log.warn("Could not parse fetched array: {}", e.getMessage());
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────
    //  TELEGRAM ALERT
    // ──────────────────────────────────────────────────────────

    private void sendTelegramAlert(VwapStraddleWatchlist item, String signalEvent,
                                   BigDecimal cp, BigDecimal cv, LocalDateTime now) {
        int targetHits = "ENTRY".equals(signalEvent) ? item.getEntryHitTarget() : item.getExitHitTarget();
        String header = "ENTRY".equals(signalEvent)
                ? "`[VWAP_SHORT_STRADDLE]` 🟢 *ENTRY*"
                : "`[VWAP_SHORT_STRADDLE]` 🔴 *EXIT*";

        String msg = String.format(
                "%s%n*Stock:* %s %s (%s)%n*CP:* %s | *CV:* %s%n*Hits:* %d / %d | *Time:* %s IST",
                header,
                item.getSymbol(),
                item.getStrike().stripTrailingZeros().toPlainString(),
                item.getExpiry().format(DISPLAY_EXPIRY_FMT),
                cp.toPlainString(),
                cv.toPlainString(),
                targetHits,
                targetHits,
                now.format(TIME_FMT)
        );

        try {
            telegramService.sendBroadcast(msg);
        } catch (Exception e) {
            log.error("Failed sending Telegram {} alert for {} {}: {}",
                    signalEvent, item.getSymbol(), item.getStrike(), e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────
    //  CRUD & UI LOOKUPS
    // ──────────────────────────────────────────────────────────

    public List<String> getAvailableNfoSymbols() {
        return watchlistRepo.findDistinctNfoSymbols();
    }

    public List<BigDecimal> getNearestExpiryStrikes(String symbol) {
        if (symbol == null || symbol.isBlank()) return List.of();

        LocalDate today = LocalDate.now(IST);
        List<Object[]> rows = watchlistRepo.findNfoExpiriesAndStrikesBySymbol(symbol.trim());

        Map<LocalDate, Set<BigDecimal>> strikesByExpiry = new TreeMap<>();
        for (Object[] row : rows) {
            LocalDate exp = parseExpiry(row[0]);
            if (exp == null || exp.isBefore(today)) continue;

            BigDecimal rawStrike = new BigDecimal(row[1].toString());
            BigDecimal normalized = rawStrike.divide(STRIKE_MULTIPLIER, 2, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            if (normalized.scale() < 0) {
                normalized = normalized.setScale(0, RoundingMode.UNNECESSARY);
            }
            strikesByExpiry.computeIfAbsent(exp, k -> new TreeSet<>()).add(normalized);
        }

        if (strikesByExpiry.isEmpty()) return List.of();
        return new ArrayList<>(strikesByExpiry.values().iterator().next());
    }

    public VwapStraddleWatchlistDto addWatchlistItem(VwapStraddleAddRequest req) {
        if (req.getSymbol() == null || req.getSymbol().isBlank() || req.getStrike() == null) {
            throw new IllegalArgumentException("Symbol and strike are required.");
        }

        String symbol = req.getSymbol().trim().toUpperCase();
        BigDecimal strike = req.getStrike().setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledStrike = strike.multiply(STRIKE_MULTIPLIER);
        LocalDate today = LocalDate.now(IST);

        LocalDate currentExpiry = watchlistRepo.findNfoExpiriesAndStrikesBySymbol(symbol).stream()
                .map(row -> parseExpiry(row[0]))
                .filter(exp -> exp != null && !exp.isBefore(today))
                .min(LocalDate::compareTo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No active NFO expiry found in indexes for " + symbol));

        List<Object[]> legRows = watchlistRepo.findNfoOptionLegs(symbol, scaledStrike);

        Map<LocalDate, Map<String, String>> legsByExpiry = new TreeMap<>();
        for (Object[] row : legRows) {
            LocalDate exp = parseExpiry(row[0]);
            if (exp == null || exp.isBefore(today)) continue;

            String tradingSymbol = row[1].toString().trim().toUpperCase();
            String token = row[2].toString().trim();

            if (tradingSymbol.endsWith("CE")) {
                legsByExpiry.computeIfAbsent(exp, k -> new HashMap<>()).put("CE", token);
            } else if (tradingSymbol.endsWith("PE")) {
                legsByExpiry.computeIfAbsent(exp, k -> new HashMap<>()).put("PE", token);
            }
        }

        Map<String, String> legs = legsByExpiry.get(currentExpiry);
        if (legs == null || !legs.containsKey("CE") || !legs.containsKey("PE")) {
            throw new IllegalArgumentException(
                    "No active CE/PE pair found in current expiry (" + currentExpiry
                            + ") for " + symbol + " strike " + strike);
        }

        if (watchlistRepo.existsByTradeDateAndSymbolIgnoreCaseAndExpiryAndStrike(
                today, symbol, currentExpiry, strike)) {
            throw new IllegalArgumentException(symbol + " " + strike.stripTrailingZeros().toPlainString()
                    + " is already in today's watchlist.");
        }

        int entryHits = (req.getEntryHitTarget() != null && req.getEntryHitTarget() > 0) ? req.getEntryHitTarget() : 5;
        int exitHits = (req.getExitHitTarget() != null && req.getExitHitTarget() > 0) ? req.getExitHitTarget() : 5;
        String alertFlag = "N".equalsIgnoreCase(req.getAlertFlag()) ? "N" : "Y";

        VwapStraddleWatchlist entity = VwapStraddleWatchlist.builder()
                .tradeDate(today)
                .symbol(symbol)
                .expiry(currentExpiry)
                .strike(strike)
                .ceToken(legs.get("CE"))
                .peToken(legs.get("PE"))
                .entryHitTarget(entryHits)
                .exitHitTarget(exitHits)
                .currentHits(0)
                .status("WAITING")
                .alertFlag(alertFlag)
                .isActive(true)
                .build();

        VwapStraddleWatchlist saved = watchlistRepo.save(entity);
        try {
            backfillIfNeeded(saved, null); // Immediately seeds 09:15 AM -> now-1m for chart & VWAP
        } catch (Exception e) {
            log.warn("Initial backfill failed for {} {} (will retry on next tick): {}",
                    symbol, strike, e.getMessage());
        }
        return toDto(saved);
    }

    public List<VwapStraddleWatchlistDto> getTodayWatchlist() {
        LocalDate today = LocalDate.now(IST);
        return watchlistRepo.findByTradeDateOrderByIdAsc(today).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public VwapStraddleWatchlistDto updateWatchlistItem(Long id, VwapStraddleUpdateRequest req) {
        VwapStraddleWatchlist item = watchlistRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Watchlist item not found: " + id));

        if (req.getEntryHitTarget() != null && req.getEntryHitTarget() > 0) {
            item.setEntryHitTarget(req.getEntryHitTarget());
        }
        if (req.getExitHitTarget() != null && req.getExitHitTarget() > 0) {
            item.setExitHitTarget(req.getExitHitTarget());
        }
        if (req.getAlertFlag() != null) {
            item.setAlertFlag("N".equalsIgnoreCase(req.getAlertFlag()) ? "N" : "Y");
        }
        if (req.getIsActive() != null) {
            item.setIsActive(req.getIsActive());
        }

        return toDto(watchlistRepo.save(item));
    }

    @Transactional
    public void deleteWatchlistItem(Long id) {
        watchlistRepo.deleteById(id);
        seriesCache.remove(id);
    }

    public List<VwapStraddleTickDto> getChartTicks(Long watchlistId) {
        SeriesBuffer buffer = seriesCache.get(watchlistId);
        return buffer == null ? List.of() : List.copyOf(buffer.ticks);
    }

    // ──────────────────────────────────────────────────────────
    //  SCANNER AUTO-ADD (ISOLATED TRANSACTION)
    // ──────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoAddAtmFromScanner(String rawSymbol, BigDecimal spotLtp) {
        if (rawSymbol == null || rawSymbol.isBlank() || spotLtp == null || spotLtp.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        String symbol = rawSymbol.trim().toUpperCase();
        LocalDate today = LocalDate.now(IST);

        // Skip silently if already added for today (prevents duplicate rows on every 15-min scan)
        if (watchlistRepo.existsByTradeDateAndSymbolIgnoreCase(today, symbol)) {
            return;
        }

        // Find nearest ATM strike for nearest active expiry
        List<BigDecimal> strikes = getNearestExpiryStrikes(symbol);
        if (strikes.isEmpty()) {
            log.warn("VWAP Straddle auto-add skipped: no NFO strikes found for {}", symbol);
            return;
        }

        BigDecimal atmStrike = strikes.stream()
                .min(Comparator.comparing(s -> s.subtract(spotLtp).abs()))
                .orElse(null);

        if (atmStrike == null) {
            return;
        }

        BigDecimal strike = atmStrike.setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledStrike = strike.multiply(STRIKE_MULTIPLIER);
        List<Object[]> legRows = watchlistRepo.findNfoOptionLegs(symbol, scaledStrike);

        Map<LocalDate, Map<String, String>> legsByExpiry = new TreeMap<>();
        for (Object[] row : legRows) {
            LocalDate exp = parseExpiry(row[0]);
            if (exp == null || exp.isBefore(today)) continue;

            String tradingSymbol = row[1].toString().trim().toUpperCase();
            String token = row[2].toString().trim();

            if (tradingSymbol.endsWith("CE")) {
                legsByExpiry.computeIfAbsent(exp, k -> new HashMap<>()).put("CE", token);
            } else if (tradingSymbol.endsWith("PE")) {
                legsByExpiry.computeIfAbsent(exp, k -> new HashMap<>()).put("PE", token);
            }
        }

        Map.Entry<LocalDate, Map<String, String>> nearest = legsByExpiry.entrySet().stream()
                .filter(e -> e.getValue().containsKey("CE") && e.getValue().containsKey("PE"))
                .findFirst()
                .orElse(null);

        if (nearest == null) {
            log.warn("VWAP Straddle auto-add skipped: incomplete CE/PE pair for {} {}", symbol, strike);
            return;
        }

        LocalDate expiry = nearest.getKey();
        if (watchlistRepo.existsByTradeDateAndSymbolIgnoreCaseAndExpiryAndStrike(today, symbol, expiry, strike)) {
            return;
        }

        VwapStraddleWatchlist entity = VwapStraddleWatchlist.builder()
                .tradeDate(today)
                .symbol(symbol)
                .expiry(expiry)
                .strike(strike)
                .ceToken(nearest.getValue().get("CE"))
                .peToken(nearest.getValue().get("PE"))
                .entryHitTarget(5)
                .exitHitTarget(5)
                .currentHits(0)
                .status("WAITING")
                .alertFlag("Y")
                .isActive(true)
                .build();

        watchlistRepo.save(entity);
        log.info("VWAP Straddle auto-added from FnoScanner: {} {} ({})", symbol, strike, expiry);
    }

    // ──────────────────────────────────────────────────────────
    //  HELPERS
    // ──────────────────────────────────────────────────────────

    private VwapStraddleWatchlistDto toDto(VwapStraddleWatchlist entity) {
        SeriesBuffer buffer = seriesCache.get(entity.getId());
        VwapStraddleTickDto latest = (buffer != null) ? buffer.latest() : null;

        return VwapStraddleWatchlistDto.builder()
                .id(entity.getId())
                .tradeDate(entity.getTradeDate())
                .symbol(entity.getSymbol())
                .expiry(entity.getExpiry())
                .strike(entity.getStrike())
                .ceToken(entity.getCeToken())
                .peToken(entity.getPeToken())
                .entryHitTarget(entity.getEntryHitTarget())
                .exitHitTarget(entity.getExitHitTarget())
                .currentHits(entity.getCurrentHits())
                .status(entity.getStatus())
                .entryCp(entity.getEntryCp())
                .alertFlag(entity.getAlertFlag())
                .isActive(entity.getIsActive())
                .liveCp(latest != null ? latest.getCombinedPrice() : null)
                .liveCv(latest != null ? latest.getCombinedVwap() : null)
                .lastTickTime(latest != null ? latest.getTime() : null)
                .build();
    }

    private LocalDate parseExpiry(Object expiryObj) {
        if (expiryObj == null) return null;
        String raw = expiryObj.toString().trim();
        if (raw.isEmpty()) return null;
        try {
            return LocalDate.parse(raw, INDEX_EXPIRY_FMT);
        } catch (Exception e) {
            log.debug("Skipping unparseable expiry '{}'", raw);
            return null;
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}