package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.StraddlePremiumDto;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.utility.ConditionalLogger;
import com.crumbs.trade.utility.ExpiryUtil;
import lombok.RequiredArgsConstructor;
import java.util.concurrent.ConcurrentHashMap;
@Service
@RequiredArgsConstructor
public class StraddleTokenService {

    private static final Logger baseLogger = LoggerFactory.getLogger(StraddleTokenService.class);
    private final ConditionalLogger logger = new ConditionalLogger(baseLogger);

    private final StrategyRepo strategyRepo;
    private final IndexesRepo indexesRepo;
    private final ChartService chartService;
    private final AngelOne angelOne;
    private LocalDate optionCacheDate;
	// State: Name -> Strike List Cache

	private final Map<String, List<StraddlePremiumDto>> strikeListCache = new ConcurrentHashMap<>();

	private final Map<String, LocalDate> strikeInitDate = new ConcurrentHashMap<>();

	private final Map<String, Optional<Indexes>> optionTokenCache =
	        new ConcurrentHashMap<>();


    public String getSymbolByName(String name) {
        if ("NIFTY".equalsIgnoreCase(name)) {
            return strategyRepo.findByName("STRADDLE_PREMIUM").getSymbol();
        } else if ("CRUDEOIL".equalsIgnoreCase(name) || "CRUDEOILM".equalsIgnoreCase(name)) {
            return strategyRepo.findByName("STRADDLE_PREMIUM").getSymbol1();
        } else if ("GOLDM".equalsIgnoreCase(name)) {
            Strategy strategy = strategyRepo.findByName(name);
            return strategy != null ? strategy.getSymbol() : "NATURALGAS";
        }
        return null;
    }

    public BigDecimal getATMStrike(String name, Strategy strategy, BigDecimal price) {
        if (price == null) return BigDecimal.ZERO;

        int stepInterval = 50; 
        if (name != null) {
            String upperName = name.toUpperCase();
            if (upperName.contains("SENSEX") || upperName.contains("BANK") || upperName.contains("CRUDEOIL")) {
                stepInterval = 100;
            } else if (upperName.contains("GOLDM")) {
                stepInterval = 500;
            }
        }
        int nearest = chartService.findNearestMultiple(price.intValue(), stepInterval);
        return BigDecimal.valueOf(nearest);
    }

    public List<StraddlePremiumDto> getOrBuildStrikeList(String name, BigDecimal atmStrike) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        if (strikeListCache.containsKey(name) && today.equals(strikeInitDate.get(name))) {
            return strikeListCache.get(name);
        }

        logger.info("Building strike list ONCE for {} using ATM {}", name, atmStrike);
        int stepInterval = 50; 
        if (name != null) {
            String upperName = name.toUpperCase();
            if (upperName.contains("SENSEX") || upperName.contains("BANK") || upperName.contains("CRUDEOIL")) {
                stepInterval = 100;
            } else if (upperName.contains("GOLDM")) {
                stepInterval = 500;
            }
        }

        int rangeValue = 600;
        if (name != null) {
            String upperName = name.toUpperCase();
            if (upperName.contains("SENSEX")) {
                rangeValue = 1000;
            } else if (upperName.contains("GOLDM")) {
                rangeValue = 500;
            }
        }

        List<StraddlePremiumDto> strikeList = buildStraddleDtos(name, atmStrike, stepInterval, rangeValue);
        strikeListCache.put(name, strikeList);
        strikeInitDate.put(name, today);
        return strikeList;
    }

    public List<StraddlePremiumDto> buildStraddleDtos(String name, BigDecimal atmStrike, int interval, int rangeValue) {
        List<StraddlePremiumDto> list = new ArrayList<>();
        BigDecimal step = BigDecimal.valueOf(interval);
        BigDecimal range = BigDecimal.valueOf(rangeValue);

        BigDecimal start = atmStrike.subtract(range);
        BigDecimal end = atmStrike.add(range);

        for (BigDecimal strike = start; strike.compareTo(end) <= 0; strike = strike.add(step)) {
            list.add(createDto(strike));
        }
        return list;
    }

    private StraddlePremiumDto createDto(BigDecimal strike) {
        StraddlePremiumDto dto = new StraddlePremiumDto();
        dto.setStrikePrice(strike);
        return dto;
    }

    public List<StraddlePremiumDto> getAllTokenDetails(List<StraddlePremiumDto> strikeList, Strategy strategy) {
    	resetOptionCacheIfNewDay();
    	logger.info("Fetching tokens for strategy: {}, raw expiry: {}", strategy.getName(), strategy.getExpiry());
        String[] normalizedExpiries = ExpiryUtil.getNormalizedExpiries(strategy.getExpiry());
        String expiryShort = normalizedExpiries[0];
        String expiryLong = normalizedExpiries[1];

        for (StraddlePremiumDto dto : strikeList) {
            int strike = dto.getStrikePrice().intValue();
            String ceSuffix = strike + "CE"; 
            String peSuffix = strike + "PE"; 


			getCachedOptionToken(strategy.getName(), ceSuffix, expiryShort,
					expiryLong).ifPresentOrElse(ceIndex -> {
                    Token t = new Token();
                    t.setToken(ceIndex.getToken());
                    t.setSymbol(ceIndex.getSymbol());
                    t.setExch_seg(ceIndex.getExchange());
                    t.setQuantity(ceIndex.getLotsize());
                    dto.setCeToken(t);
                    logger.info("Found CE token for suffix {}: {} ({})", ceSuffix, t.getToken(), ceIndex.getSymbol());
                }, () -> logger.info("CE token NOT found for name: {}, suffix: {}, expiries: [{}, {}]", strategy.getName(), ceSuffix, expiryShort, expiryLong));


			getCachedOptionToken(strategy.getName(), peSuffix, expiryShort,
					expiryLong).ifPresentOrElse(peIndex -> {
						Token t = new Token();
						t.setToken(peIndex.getToken());
						t.setSymbol(peIndex.getSymbol());
						t.setExch_seg(peIndex.getExchange());
						t.setQuantity(peIndex.getLotsize());
						dto.setPeToken(t);
						logger.info("Found PE token for suffix {}: {} ({})",
								peSuffix, t.getToken(), peIndex.getSymbol());
					}, () -> logger.info(
							"PE token NOT found for name: {}, suffix: {}, expiries: [{}, {}]",
							strategy.getName(), peSuffix, expiryShort,
							expiryLong));
		}
        return strikeList;
    }

    public String generateSymbol(String strategyName, String expiry, int strike, String optionType) {
        if (strategyName == null || expiry == null) return "";
        String upperName = strategyName.toUpperCase().trim();
        String cleanExpiry = expiry.toUpperCase().trim();

        if ("SENSEX".equals(upperName)) {
            Pattern pattern = Pattern.compile("^(\\d{1,2})([A-Z]{3})(\\d{2})$");
            Matcher matcher = pattern.matcher(cleanExpiry);
            if (matcher.matches()) {
                int dayInt = Integer.parseInt(matcher.group(1));
                if (dayInt >= 26) { 
                    String monthlyExpiryPattern = matcher.group(3) + matcher.group(2); 
                    return String.format("%s%s%d%s", upperName, monthlyExpiryPattern, strike, optionType);
                }
            }
        }
        return String.format("%s%s%d%s", upperName, cleanExpiry, strike, optionType);
    }

    public static String normalizeExpiry(String shortExpiry) {
        if (shortExpiry == null || shortExpiry.length() != 7) throw new IllegalArgumentException("Invalid expiry format");
        return shortExpiry.substring(0, 2) + shortExpiry.substring(2, 5) + (Integer.parseInt(shortExpiry.substring(5, 7)) + 2000);
    }

    public List<StraddlePremiumDto> getOnlyAtmStrikeList(BigDecimal atmStrike) {
        StraddlePremiumDto dto = new StraddlePremiumDto();
        dto.setStrikePrice(atmStrike);
        return Collections.singletonList(dto);
    }
    private Optional<Indexes> getCachedOptionToken(
            String name,
            String strikeSuffix,
            String expiryShort,
            String expiryLong) {

        String key =
                name + "|" +
                strikeSuffix + "|" +
                expiryShort + "|" +
                expiryLong;

        return optionTokenCache.computeIfAbsent(
                key,
                k -> indexesRepo.findOptionToken(
                        name,
                        strikeSuffix,
                        expiryShort,
                        expiryLong
                )
        );
    }
    private void resetOptionCacheIfNewDay() {

        LocalDate today =
                LocalDate.now(ZoneId.of("Asia/Kolkata"));

        if (optionCacheDate == null
                || !optionCacheDate.equals(today)) {

            optionTokenCache.clear();
            optionCacheDate = today;

            logger.info(
                    "Option token cache reset for {}",
                    today
            );
        }
    }
}