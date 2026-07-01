package com.crumbs.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.StrategyRepo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@Service 
public class StrategyRolloverService {

    private static final Logger logger = LoggerFactory.getLogger(StrategyRolloverService.class);

    @Autowired
    private StrategyRepo strategyRepository;

    @Autowired
    private IndexesRepo indexRepository;

    // Formatter to cleanly save the new date back to the Strategy table (e.g., "28JUL26")
    private final DateTimeFormatter saveFormatter = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("ddMMMyy")
            .toFormatter(Locale.ENGLISH);

    /**
     * SMART PARSER: Tries multiple formats so the application never crashes
     * on mixed database data like "16JUL26" vs "2026-07-16"
     */
    private LocalDate parseDateSafely(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty() || dateStr.equalsIgnoreCase("null")) {
            return null;
        }
        dateStr = dateStr.trim();

        DateTimeFormatter[] formatters = new DateTimeFormatter[]{
                new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("ddMMMyy").toFormatter(Locale.ENGLISH),    // matches "07JUL26"
                new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("yyyy-MM-dd").toFormatter(Locale.ENGLISH), // matches "2026-07-28"
                new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("ddMMMyyyy").toFormatter(Locale.ENGLISH)   // matches "07JUL2026"
        };

        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDate.parse(dateStr, formatter);
            } catch (Exception e) {
                // Ignore the error and try the next format
            }
        }
        
        throw new IllegalArgumentException("Unrecognized date format: " + dateStr);
    }

    // Change to fixedDelay = 10000 for local testing, keep Cron for Production
    @Scheduled(cron = "0 30 23 * * MON-FRI") // Runs at 11:30 PM
    //@Scheduled(fixedDelay = 10000)
    @Transactional
    public void rollOverExpiredStrategies() {
        logger.info("=====================================================");
        logger.info("Starting Daily EOD Strategy Rollover Job at 11:30 PM...");
        logger.info("=====================================================");
        
        LocalDate today = LocalDate.now();
        List<Strategy> strategies = strategyRepository.findAll();
        int updatedCount = 0;

        for (Strategy strategy : strategies) {
            String expiryStr = strategy.getExpiry();
            
            // Skip rows with no valid expiry date
            if (expiryStr == null || expiryStr.trim().isEmpty() || expiryStr.equalsIgnoreCase("null")) {
                continue; 
            }

            try {
                // 1. Parse the date using the smart parser
                LocalDate expiryDate = parseDateSafely(expiryStr);

                // 2. If the date is successfully parsed and is today or in the past, roll it over
                if (expiryDate != null && !expiryDate.isAfter(today)) {
                    boolean success = rollOverToNextExpiry(strategy, today);
                    if (success) {
                        updatedCount++;
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to parse Strategy expiry date for ID {}: {}", strategy.getId(), expiryStr);
            }
        }
        
        logger.info("=====================================================");
        logger.info("Rollover Job Completed. Total strategies updated: {}", updatedCount);
        logger.info("=====================================================");
    }

    private boolean rollOverToNextExpiry(Strategy strategy, LocalDate today) {
        String oldSymbol = strategy.getSymbol();
        String oldTradingSymbol = strategy.getTradingsymbol();
        String oldExpiry = strategy.getExpiry();
        String oldToken = strategy.getToken();

        // 1. STRICT FUTURES CHECK
        boolean isFut = (oldSymbol != null && oldSymbol.contains("FUT")) || 
                        (oldTradingSymbol != null && oldTradingSymbol.contains("FUT"));

        if (!isFut) {
            logger.info("Skipping Rollover: Not a Futures contract -> Symbol: {}", oldSymbol);
            return false;
        }

        // 2. Fetch ALL records for this Index (This includes Options, allowing us to see Weekly expiries)
        List<Indexes> allIndices = indexRepository.findByName(strategy.getName());

        if (allIndices.isEmpty()) {
            logger.warn("[ROLLOVER FAILED] No data found in Indexes for {}", strategy.getName());
            return false;
        }

        // 3. Find the ABSOLUTE NEAREST Expiry (This will be the upcoming Weekly Expiry)
        Optional<LocalDate> nearestWeeklyExpiryOpt = allIndices.stream()
                .map(idx -> {
                    try { return parseDateSafely(idx.getExpiry()); } 
                    catch (Exception e) { return null; }
                })
                .filter(date -> date != null && date.isAfter(today))
                .min(LocalDate::compareTo);

        // 4. Filter the list down to ONLY Futures contracts
        List<Indexes> futureContracts = allIndices.stream()
                .filter(idx -> idx.getSymbol() != null && idx.getSymbol().contains("FUT"))
                .collect(Collectors.toList());

        // 5. Find the nearest FUT Expiry (This will be the active Monthly Future)
        Optional<LocalDate> nearestFutExpiryOpt = futureContracts.stream()
                .map(idx -> {
                    try { return parseDateSafely(idx.getExpiry()); } 
                    catch (Exception e) { return null; }
                })
                .filter(date -> date != null && date.isAfter(today))
                .min(LocalDate::compareTo);

        // 6. Validate and Apply the Split Logic
        if (nearestWeeklyExpiryOpt.isPresent() && nearestFutExpiryOpt.isPresent()) {
            LocalDate nearestWeeklyExpiry = nearestWeeklyExpiryOpt.get();
            LocalDate nearestFutExpiry = nearestFutExpiryOpt.get();

            // Find the specific Monthly FUT row to grab its Token and Symbol
            List<Indexes> exactFutMatches = futureContracts.stream()
                    .filter(idx -> {
                        try {
                            LocalDate d = parseDateSafely(idx.getExpiry());
                            return d != null && d.isEqual(nearestFutExpiry);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());

            if (exactFutMatches.size() == 1) {
                Indexes nextFutContract = exactFutMatches.get(0); 

                // ⭐ SET EXPIRY TO WEEKLY DATE
                String formattedWeeklyExpiry = nearestWeeklyExpiry.format(saveFormatter).toUpperCase();
                strategy.setExpiry(formattedWeeklyExpiry);
                
                // ⭐ SET SYMBOL & TOKEN TO MONTHLY FUT CONTRACT
                strategy.setToken(nextFutContract.getToken());
                if (nextFutContract.getSymbol() != null) {
                    strategy.setSymbol(nextFutContract.getSymbol()); 
                    strategy.setTradingsymbol(nextFutContract.getSymbol());
                }
                
                strategyRepository.save(strategy);
                
                logger.info("[ROLLOVER SUCCESS] {} rolled over.", strategy.getName());
                logger.info("   -> OLD : Expiry={}, TradingSymbol={}, Token={}", oldExpiry, oldTradingSymbol, oldToken);
                logger.info("   -> NEW : Expiry (Weekly)={}, TradingSymbol (Monthly)={}, Token={}", 
                        formattedWeeklyExpiry, nextFutContract.getSymbol(), nextFutContract.getToken());
                
                return true;
                
            } else if (exactFutMatches.size() > 1) {
                logger.error("[DATA ISSUE] Rollover Aborted for {}. Found {} duplicate FUT contracts.", 
                        strategy.getName(), exactFutMatches.size());
                return false;
            }
            
        } else {
            logger.warn("[ROLLOVER FAILED] Could not resolve future dates for {}", strategy.getName());
        }
        
        return false;
    }
}