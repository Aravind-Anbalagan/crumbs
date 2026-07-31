package com.crumbs.trade.advisory;

import com.crumbs.trade.broker.Samco;
import com.crumbs.trade.dto.SamcoOptionChainResponse;
import com.crumbs.trade.utility.SamcoSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdvisoryOiService {

    private final Samco samco;
    private final SamcoSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    // 🚀 Holds the complete details for a specific Strike (including Greeks)
    public record StrikeDetails(
            BigDecimal strike,
            long openInterest,
            BigDecimal ltp,
            double delta,
            double theta,
            double iv
    ) {}

    // 🚀 Holds both the Call Wall and Put Wall with their respective Greeks
    public record AdvisoryOiData(
            StrikeDetails putWall,
            StrikeDetails callWall
    ) {}

    public AdvisoryOiData fetchLiveOiAndGreeks(String symbol, String exchange, String expiry) {
        log.info("📊 Fetching Live OI & Greeks for {} {}...", symbol, expiry);

        StrikeDetails maxPut = null;
        StrikeDetails maxCall = null;
        long maxPutOi = 0L;
        long maxCallOi = 0L;

        try {
            String sessionToken = sessionManager.getSession(); // or getSamcoSession() based on your setup

            // Fetch Option Chain JSON from Samco
            String jsonResponse = samco.getOptionChain(sessionToken, exchange, symbol, expiry, null, null);

            if (jsonResponse == null || jsonResponse.isEmpty()) {
                log.warn("⚠️ Samco returned empty option chain for {}", symbol);
                return new AdvisoryOiData(null, null);
            }

            // 🚀 Using your GreekStrategyService ObjectMapper Logic
            SamcoOptionChainResponse response = objectMapper.readValue(jsonResponse, SamcoOptionChainResponse.class);

            if ("Success".equalsIgnoreCase(response.status()) && response.optionChainDetails() != null) {

                for (var detail : response.optionChainDetails()) {
                    long currentOi = parseLongSafely(detail.openInterest());
                    BigDecimal currentStrike = new BigDecimal(detail.strikePrice());
                    BigDecimal ltp = new BigDecimal(detail.lastTradedPrice() != null ? detail.lastTradedPrice() : "0");

                    double delta = parseDoubleSafely(detail.delta());
                    double theta = parseDoubleSafely(detail.theta());
                    double iv = parseDoubleSafely(detail.impliedVolatility());

                    // Check for Put Wall
                    if ("PE".equalsIgnoreCase(detail.optionType()) && currentOi > maxPutOi) {
                        maxPutOi = currentOi;
                        maxPut = new StrikeDetails(currentStrike, currentOi, ltp, delta, theta, iv);
                    }
                    // Check for Call Wall
                    else if ("CE".equalsIgnoreCase(detail.optionType()) && currentOi > maxCallOi) {
                        maxCallOi = currentOi;
                        maxCall = new StrikeDetails(currentStrike, currentOi, ltp, delta, theta, iv);
                    }
                }

                if (maxPut != null && maxCall != null) {
                    log.info("🧱 Found PUT Wall: Strike {} (OI: {}, LTP: {}, Delta: {})",
                            maxPut.strike(), maxPut.openInterest(), maxPut.ltp(), maxPut.delta());
                    log.info("🧱 Found CALL Wall: Strike {} (OI: {}, LTP: {}, Delta: {})",
                            maxCall.strike(), maxCall.openInterest(), maxCall.ltp(), maxCall.delta());
                }

            } else {
                log.error("❌ Samco API Error: {}", response.statusMessage());
            }

        } catch (Exception e) {
            log.error("❌ Failed to parse OI and Greeks for {}: {}", symbol, e.getMessage(), e);
        }

        return new AdvisoryOiData(maxPut, maxCall);
    }

    // 🚀 Exact helper methods from your GreekStrategyService
    private Double parseDoubleSafely(String value) {
        try {
            return (value == null || value.trim().isEmpty() || "NA".equalsIgnoreCase(value)) ? 0.0 : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private long parseLongSafely(String value) {
        try {
            return (value == null || value.trim().isEmpty() || "NA".equalsIgnoreCase(value)) ? 0L : (long) Double.parseDouble(value); // Double parse first to handle floats safely
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}