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

    private volatile LocalDate lastCacheDate = LocalDate.now();

    private final Map<String, List<Indexes>> dailyRawContractsCache = new ConcurrentHashMap<>();
    private final Map<String, ScannedContractDto> statefulContractCache = new ConcurrentHashMap<>();

    public List<ScannedContractDto> scanEligibleContractsList(String underlyingName, OptionScannerConfig config) {
        try {
            Map<String, ScannedContractDto> map = scanEligibleContractsMap(underlyingName, config);
            if (map == null || map.isEmpty()) {
                return Collections.emptyList();
            }
            return map.values().stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator
                            .comparing(ScannedContractDto::getExpiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparingDouble(ScannedContractDto::getStrike)
                            .thenComparing(ScannedContractDto::getOptionType, Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("❌ Error in scanEligibleContractsList for {}: {}. Returning empty list.", underlyingName, e.getMessage());
            return Collections.emptyList();
        }
    }

    public Map<String, ScannedContractDto> scanEligibleContractsMap(String underlyingName, OptionScannerConfig config) {
        try {
            BigDecimal spot = getSpotPrice(underlyingName);

            if (spot == null || spot.compareTo(BigDecimal.ZERO) <= 0) {
                logger.error("❌ Unable to fetch valid spot price for underlying: {}", underlyingName);
                return Collections.emptyMap();
            }

            return scanEligibleContractsMap(underlyingName, spot.doubleValue(), config);
        } catch (Exception e) {
            logger.error("❌ Exception in scanEligibleContractsMap for {}: {}. Returning empty map.", underlyingName, e.getMessage());
            return Collections.emptyMap();
        }
    }

    public Map<String, ScannedContractDto> scanEligibleContractsMap(String underlyingName, double spotLtp, OptionScannerConfig config) {
        try {
            List<Indexes> rawContracts = getDailyRawContracts(underlyingName);
            if (rawContracts == null || rawContracts.isEmpty()) {
                logger.warn("⚠️ No contracts found in indexes table for {}. Skipping safely.", underlyingName);
                return Collections.emptyMap();
            }

            LocalDate today = LocalDate.now();
            LocalDate maxExpiryCutoff = today.plusMonths(config.getMonthsToScan());

            Map<String, LocalDate> parsedExpiries = new HashMap<>();
            for (Indexes c : rawContracts) {
                if (c == null) continue;
                String rawExp = c.getExpiry() != null ? c.getExpiry().trim() : "";
                if (!rawExp.isEmpty() && !parsedExpiries.containsKey(rawExp)) {
                    try {
                        LocalDate parsed = LocalDate.parse(rawExp, EXPIRY_FORMATTER);
                        if (!parsed.isBefore(today) && !parsed.isAfter(maxExpiryCutoff)) {
                            parsedExpiries.put(rawExp, parsed);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            if (parsedExpiries.isEmpty()) {
                logger.warn("⚠️ No future expiries found within {} months for {}", config.getMonthsToScan(), underlyingName);
                return Collections.emptyMap();
            }

            Map<YearMonth, LocalDate> monthlyExpiryMap = parsedExpiries.values().stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(
                            YearMonth::from,
                            date -> date,
                            (d1, d2) -> d1.isAfter(d2) ? d1 : d2,
                            HashMap::new
                    ));

            boolean isStock = isEquityStock(underlyingName);

            Set<String> allowedExpiryStrings = new HashSet<>();
            for (Map.Entry<String, LocalDate> entry : parsedExpiries.entrySet()) {
                String rawExp = entry.getKey();
                LocalDate date = entry.getValue();
                LocalDate monthlyOpt = monthlyExpiryMap.get(YearMonth.from(date));
                boolean isMonthly = monthlyOpt != null && monthlyOpt.equals(date);

                if (isMonthly && (config.isScanMonthly() || isStock)) {
                    allowedExpiryStrings.add(rawExp);
                } else if (!isMonthly && config.isScanWeekly()) {
                    allowedExpiryStrings.add(rawExp);
                }
            }

            List<Double> allStrikes = rawContracts.stream()
                    .filter(Objects::nonNull)
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
            if (atmIndex < 0) atmIndex = 0;

            int minIndex = Math.max(0, atmIndex - config.getStrikeDistance());
            int maxIndex = Math.min(allStrikes.size() - 1, atmIndex + config.getStrikeDistance());

            Set<Double> eligibleStrikes = new HashSet<>();
            if (minIndex <= maxIndex) {
                eligibleStrikes.addAll(allStrikes.subList(minIndex, maxIndex + 1));
            }

            Map<String, ScannedContractDto> resultMap = new HashMap<>();
            LocalDateTime now = LocalDateTime.now();

            for (Indexes c : rawContracts) {
                if (c == null) continue;
                String rawExp = c.getExpiry() != null ? c.getExpiry().trim() : "";
                if (!allowedExpiryStrings.contains(rawExp)) continue;

                double strike = normalizeStrike(c);
                if (!eligibleStrikes.contains(strike)) continue;

                String optType = extractOptionType(c.getSymbol());
                if (optType == null) continue;

                OptionScannerConfig.Moneyness moneyness = determineMoneyness(optType, strike, atmStrike);
                if (config.getAllowedMoneyness() != null && !config.getAllowedMoneyness().contains(moneyness)) continue;

                if (c.getToken() == null) continue;

                ScannedContractDto dto = statefulContractCache.computeIfAbsent(c.getToken(), tokenKey -> {
                    LocalDate expDate = parsedExpiries.get(rawExp);
                    LocalDate monthlyOpt = expDate != null ? monthlyExpiryMap.get(YearMonth.from(expDate)) : null;
                    boolean isMonthly = monthlyOpt != null && monthlyOpt.equals(expDate);
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

                dto.setMoneyness(moneyness);
                dto.setSpotPrice(BigDecimal.valueOf(spotLtp));
                dto.setLastEvaluatedAt(now);

                resultMap.put(dto.getToken(), dto);
            }

            return resultMap;

        } catch (Exception e) {
            logger.error("❌ Error scanning contracts for underlying {}: {}. Continuing execution.", underlyingName, e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    private BigDecimal getSpotPrice(String name) {
        try {
            String session = sessionManager.getSession();
            String symbol = name.trim().toUpperCase();

            if (symbol.equals("NIFTY")
                    || symbol.equals("SENSEX")
                    || symbol.contains("BANK")
                    || symbol.contains("FINNIFTY")
                    || symbol.contains("MIDCPNIFTY")) {
                return samco.getIndexPrice(session, symbol);
            }

            if (symbol.startsWith("CRUDEOIL") || symbol.startsWith("GOLD")) {
                String futSymbol = tokenService.getSymbolByName(symbol);
                if (futSymbol != null) {
                    return samco.getLtp(session, "MCX", futSymbol);
                } else {
                    logger.error("❌ Could not resolve Futures symbol for Commodity: {}", symbol);
                    return null;
                }
            }

            BigDecimal stockSpot = samco.getLtp(session, "NSE", symbol);
            if (stockSpot != null && stockSpot.compareTo(BigDecimal.ZERO) > 0) {
                return stockSpot;
            }
        } catch (Exception e) {
            logger.warn("⚠️ Exception fetching spot price for {}: {}", name, e.getMessage());
        }
        return null;
    }

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

        // Safe manual check instead of relying purely on computeIfAbsent to avoid thread blocks or locking issues
        List<Indexes> cachedContracts = dailyRawContractsCache.get(underlyingName);
        if (cachedContracts != null) {
            return cachedContracts;
        }

        synchronized (this) {
            cachedContracts = dailyRawContractsCache.get(underlyingName);
            if (cachedContracts != null) {
                return cachedContracts;
            }

            String exchange = (underlyingName.toUpperCase().contains("GOLD") || underlyingName.toUpperCase().contains("CRUDE"))
                    ? "MCX"
                    : "NFO";

            logger.info("💾 Caching raw {} contracts from DB for {}...", exchange, underlyingName);

            try {
                List<Indexes> contracts = indexesRepo.findOptionContractsByNameAndExchange(underlyingName, exchange);
                if (contracts == null || contracts.isEmpty()) {
                    logger.warn("⚠️ No contracts found via primary query for [Name: {}, Exchange: {}].", underlyingName, exchange);
                    contracts = Collections.emptyList();
                }
                dailyRawContractsCache.put(underlyingName, contracts);
                return contracts;
            } catch (Exception e) {
                logger.error("❌ DB query failed when fetching contracts for {}: {}", underlyingName, e.getMessage());
                List<Indexes> emptyList = Collections.emptyList();
                dailyRawContractsCache.put(underlyingName, emptyList);
                return emptyList;
            }
        }
    }

    private double normalizeStrike(Indexes contract) {
        try {
            if (contract.getStrike() == null) return -1.0;
            double raw = Double.parseDouble(contract.getStrike().trim());
            return raw / BROKER_STRIKE_DIVISOR;
        } catch (Exception e) {
            return -1.0;
        }
    }

    private double findAtmStrike(List<Double> sortedStrikes, double spotLtp) {
        if (sortedStrikes == null || sortedStrikes.isEmpty()) return spotLtp;
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
        if (symbol == null) return false;
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