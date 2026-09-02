package com.crumbs.trade.service;

import com.crumbs.trade.broker.Samco;
import com.crumbs.trade.builder.OptionScannerConfig;
import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.utility.SamcoSessionManager;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OptionChainScannerService {

    private static final Logger logger = LogManager.getLogger(OptionChainScannerService.class);

    // Case-insensitive formatter to parse uppercase expiries like "08SEP2026" or "01SEP2026"
    private static final DateTimeFormatter EXPIRY_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("ddMMMyyyy")
            .toFormatter(Locale.ENGLISH);

    private static final double BROKER_STRIKE_DIVISOR = 100.0;

    private final IndexesRepo indexesRepo;
    private final Samco samco;
    private final SamcoSessionManager sessionManager;
    private final StrategyRepo strategyRepo;
    private final StraddleTokenService tokenService;

    // =========================================================
    // 🧠 IN-MEMORY STATE & DAILY CACHE
    // =========================================================
    private volatile LocalDate lastCacheDate = LocalDate.now();

    // Caches the raw DB contracts per underlying (Fetched ONCE per day)
    private final Map<String, List<Indexes>> dailyRawContractsCache = new ConcurrentHashMap<>();

    // Caches the stateful DTOs per token (Preserves RSI hook counts)
    private final Map<String, ScannedContractDto> statefulContractCache = new ConcurrentHashMap<>();

    // =========================================================
    // 🚀 PUBLIC SCANNER API
    // =========================================================

    /**
     * Primary entry point: Scans and returns an ordered LIST of eligible strikes.
     * Sorted by: Expiry Date -> Strike Price -> Option Type (CE then PE).
     *
     * @param underlyingName Symbol (e.g. "NIFTY", "BANKNIFTY", "RELIANCE", "TCS")
     * @param config         Filtering configuration
     * @return List of ScannedContractDto
     */
    public List<ScannedContractDto> scanEligibleContractsList(String underlyingName, OptionScannerConfig config) {
        Map<String, ScannedContractDto> map = scanEligibleContractsMap(underlyingName, config);
        return map.values().stream()
                .sorted(Comparator
                        .comparing(ScannedContractDto::getExpiryDate)
                        .thenComparingDouble(ScannedContractDto::getStrike)
                        .thenComparing(ScannedContractDto::getOptionType))
                .collect(Collectors.toList());
    }

    /**
     * Scans and returns a Map of eligible contracts keyed by Instrument Token.
     * Automatically retrieves live spot price from Samco for Indices or Stocks.
     */
    public Map<String, ScannedContractDto> scanEligibleContractsMap(String underlyingName, OptionScannerConfig config) {
        BigDecimal spot = getSpotPrice(underlyingName);

        if (spot == null || spot.compareTo(BigDecimal.ZERO) <= 0) {
            logger.error("❌ Unable to fetch valid spot price for underlying: {}", underlyingName);
            return Collections.emptyMap();
        }

        return scanEligibleContractsMap(underlyingName, spot.doubleValue(), config);
    }

    /**
     * Core scanner engine.
     */
    public Map<String, ScannedContractDto> scanEligibleContractsMap(String underlyingName, double spotLtp, OptionScannerConfig config) {

        // 1. Fetch from Memory Cache (or DB if it's empty / new day)
        List<Indexes> rawContracts = getDailyRawContracts(underlyingName);
        if (rawContracts.isEmpty()) {
            logger.warn("⚠️ No contracts found in indexes table for {}", underlyingName);
            return Collections.emptyMap();
        }

        LocalDate today = LocalDate.now();
        LocalDate maxExpiryCutoff = today.plusMonths(config.getMonthsToScan());

        // 2. Parse and extract valid future expiry dates
        Map<String, LocalDate> parsedExpiries = new HashMap<>();
        for (Indexes c : rawContracts) {
            String rawExp = c.getExpiry() != null ? c.getExpiry().trim() : "";
            if (!rawExp.isEmpty() && !parsedExpiries.containsKey(rawExp)) {
                try {
                    LocalDate parsed = LocalDate.parse(rawExp, EXPIRY_FORMATTER);
                    if (!parsed.isBefore(today) && !parsed.isAfter(maxExpiryCutoff)) {
                        parsedExpiries.put(rawExp, parsed);
                    }
                } catch (Exception e) {
                    logger.debug("Skipping unparseable expiry format: {}", rawExp);
                }
            }
        }

        if (parsedExpiries.isEmpty()) {
            logger.warn("⚠️ No future expiries found within {} months for {}", config.getMonthsToScan(), underlyingName);
            return Collections.emptyMap();
        }

        // 3. Classify Expiries as Monthly vs Weekly dynamically
        Map<YearMonth, LocalDate> monthlyExpiryMap = parsedExpiries.values().stream()
                .collect(Collectors.toMap(
                        YearMonth::from,
                        date -> date,
                        (d1, d2) -> d1.isAfter(d2) ? d1 : d2
                ));

        // Check if underlying is an equity stock (Stocks in India ONLY have monthly expiries)
        boolean isStock = isEquityStock(underlyingName);

        Set<String> allowedExpiryStrings = new HashSet<>();
        for (Map.Entry<String, LocalDate> entry : parsedExpiries.entrySet()) {
            String rawExp = entry.getKey();
            LocalDate date = entry.getValue();
            boolean isMonthly = date.equals(monthlyExpiryMap.get(YearMonth.from(date)));

            // For stocks, allow expiry if monthly is enabled OR as a safe fallback
            if (isMonthly && (config.isScanMonthly() || isStock)) {
                allowedExpiryStrings.add(rawExp);
            } else if (!isMonthly && config.isScanWeekly()) {
                allowedExpiryStrings.add(rawExp);
            }
        }

        // 4. Extract unique strikes & dynamically detect ATM
        List<Double> allStrikes = rawContracts.stream()
                .map(this::normalizeStrike)
                .filter(s -> s > 0)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        if (allStrikes.isEmpty()) {
            logger.warn("⚠️ No valid strikes found for {}", underlyingName);
            return Collections.emptyMap();
        }

        double atmStrike = findAtmStrike(allStrikes, spotLtp);
        int atmIndex = allStrikes.indexOf(atmStrike);

        // Calculate Strike Bounds (ATM ± StrikeDistance)
        int minIndex = Math.max(0, atmIndex - config.getStrikeDistance());
        int maxIndex = Math.min(allStrikes.size() - 1, atmIndex + config.getStrikeDistance());

        Set<Double> eligibleStrikes = new HashSet<>(allStrikes.subList(minIndex, maxIndex + 1));

        // 5. Filter, Classify, and Build DTOs
        Map<String, ScannedContractDto> resultMap = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        for (Indexes c : rawContracts) {
            String rawExp = c.getExpiry() != null ? c.getExpiry().trim() : "";
            if (!allowedExpiryStrings.contains(rawExp)) continue;

            double strike = normalizeStrike(c);
            if (!eligibleStrikes.contains(strike)) continue;

            String optType = extractOptionType(c.getSymbol());
            if (optType == null) continue;

            OptionScannerConfig.Moneyness moneyness = determineMoneyness(optType, strike, atmStrike);
            if (!config.getAllowedMoneyness().contains(moneyness)) continue;

            // Preserve stateful DTO in cache or create fresh
            ScannedContractDto dto = statefulContractCache.computeIfAbsent(c.getToken(), tokenKey -> {
                LocalDate expDate = parsedExpiries.get(rawExp);
                boolean isMonthly = expDate.equals(monthlyExpiryMap.get(YearMonth.from(expDate)));
                int lotsize = c.getLotsize() > 0 ? c.getLotsize() : 1;

                return ScannedContractDto.builder()
                        .name(c.getName())
                        .symbol(c.getSymbol())
                        .token(c.getToken())
                        .strike(strike)
                        .exchange(c.getExchange())
                        .optionType(optType)
                        .expiryDate(expDate)
                        .rawExpiry(rawExp)
                        .isMonthly(isMonthly)
                        .lotsize(lotsize)
                        .isRSIAbove80(false)
                        .isRSIBelow20(false)
                        .aboveRSI80Count(0)
                        .belowRSI20Count(0)
                        .signalAction(ScannedContractDto.SignalAction.NONE)
                        .build();
            });

            // Update live fields
            dto.setMoneyness(moneyness);
            dto.setSpotPrice(BigDecimal.valueOf(spotLtp));
            dto.setLastEvaluatedAt(now);

            resultMap.put(dto.getToken(), dto);
        }

        return resultMap;
    }

    // =========================================================
    // 🌐 SPOT PRICE FETCHER (INDEX, COMMODITY & EQUITIES)
    // =========================================================

    /**
     * Universal Spot Price resolver:
     * - Indices: samco.getIndexPrice
     * - Commodities: samco.getLtp via Strategy exchange
     * - Equity Stocks: samco.getLtp via NSE Cash
     */
    private BigDecimal getSpotPrice(String name) {
        String session = sessionManager.getSession();
        String symbol = name.trim().toUpperCase();

        // 1. Broad Market Indices
        if (symbol.equals("NIFTY")
                || symbol.equals("SENSEX")
                || symbol.contains("BANK")
                || symbol.contains("FINNIFTY")
                || symbol.contains("MIDCPNIFTY")) {
            return samco.getIndexPrice(session, symbol);
        }

        // 2. Commodities (MCX)
        if (symbol.startsWith("CRUDEOIL") || symbol.startsWith("GOLD")) {
            Strategy strategy = strategyRepo.findByName(symbol);
            if (strategy != null) {
                return samco.getLtp(
                        session,
                        strategy.getExchange(),
                        tokenService.getSymbolByName(symbol)
                );
            }
        }

        // 3. F&O Equity Stocks (NSE Cash Spot, e.g., RELIANCE, TCS, INFY)
        try {
            BigDecimal stockSpot = samco.getLtp(session, "NSE", symbol);
            if (stockSpot != null && stockSpot.compareTo(BigDecimal.ZERO) > 0) {
                return stockSpot;
            }
        } catch (Exception e) {
            logger.warn("⚠️ Stock spot lookup via NSE cash failed for {}: {}", symbol, e.getMessage());
        }

        return null;
    }

    // =========================================================
    // 🛠️ CACHE & DATA UTILITIES
    // =========================================================

    private List<Indexes> getDailyRawContracts(String underlyingName) {
        LocalDate today = LocalDate.now();

        if (!today.equals(lastCacheDate)) {
            synchronized (this) {
                if (!today.equals(lastCacheDate)) {
                    logger.info("🌅 New trading day detected. Clearing stateful caches.");
                    dailyRawContractsCache.clear();
                    statefulContractCache.clear();
                    lastCacheDate = today;
                }
            }
        }

        return dailyRawContractsCache.computeIfAbsent(underlyingName, key -> {
            logger.info("💾 Caching raw NFO contracts from DB for {}...", key);
            return indexesRepo.findNfoContractsByName(key);
        });
    }

    /**
     * Normalizes strike values across indices and stocks.
     * Broker master dumps store strike with 2 implied decimals (e.g. 2375000 -> 23750.0).
     */
    private double normalizeStrike(Indexes contract) {
        try {
            double raw = Double.parseDouble(contract.getStrike().trim());
            return raw / BROKER_STRIKE_DIVISOR;
        } catch (Exception e) {
            return -1.0;
        }
    }

    private double findAtmStrike(List<Double> sortedStrikes, double spotLtp) {
        return sortedStrikes.stream()
                .min(Comparator.comparingDouble(s -> Math.abs(s - spotLtp)))
                .orElse(spotLtp);
    }

    private String extractOptionType(String symbol) {
        if (symbol == null) return null;
        String upper = symbol.trim().toUpperCase();
        if (upper.endsWith("CE")) return "CE";
        if (upper.endsWith("PE")) return "PE";
        return null;
    }

    private OptionScannerConfig.Moneyness determineMoneyness(String optionType, double strike, double atmStrike) {
        if (Double.compare(strike, atmStrike) == 0) {
            return OptionScannerConfig.Moneyness.ATM;
        }
        if ("CE".equalsIgnoreCase(optionType)) {
            return strike < atmStrike ? OptionScannerConfig.Moneyness.ITM : OptionScannerConfig.Moneyness.OTM;
        } else {
            return strike > atmStrike ? OptionScannerConfig.Moneyness.ITM : OptionScannerConfig.Moneyness.OTM;
        }
    }

    private boolean isEquityStock(String symbol) {
        String s = symbol.toUpperCase();
        return !s.equals("NIFTY")
                && !s.equals("SENSEX")
                && !s.contains("BANK")
                && !s.contains("FINNIFTY")
                && !s.contains("MIDCPNIFTY")
                && !s.startsWith("CRUDE")
                && !s.startsWith("GOLD");
    }
}