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
            StrikeDetails callWall,
            String expiry
    ) {}

    public AdvisoryOiData fetchLiveOiAndGreeks(String symbol, String exchange, String expiry) {
        log.info("📊 Fetching Live OI & Greeks for {} {}...", symbol, expiry);

        StrikeDetails maxPut = null;
        StrikeDetails maxCall = null;
        long maxPutOi = 0L;
        long maxCallOi = 0L;

        String resolvedExpiry = expiry;
        String jsonResponse = null;

        // 🚀 FIX: Incremental Backoff Retry Mechanism (max 4 attempts)
        int maxRetries = 4;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String sessionToken = sessionManager.getSession();
                jsonResponse = samco.getOptionChain(sessionToken, exchange, symbol, expiry, null, null);
                break; // API call succeeded! Break out of the retry loop.
            } catch (Exception e) {
                boolean isRateLimit = e.getMessage() != null && e.getMessage().contains("429");

                if (isRateLimit) {
                    log.warn("⏳ [Attempt {}/{}] Rate Limit (429) hit for {}. Breathing before retry...", attempt, maxRetries, symbol);
                } else {
                    log.warn("⏳ [Attempt {}/{}] Fetch failed for {}: {}. Breathing before retry...", attempt, maxRetries, symbol, e.getMessage());
                }

                if (attempt == maxRetries) {
                    log.error("❌ Exhausted all {} retries for {}. Skipping OI fetch.", maxRetries, symbol);
                    return new AdvisoryOiData(null, null, resolvedExpiry);
                }

                try {
                    // Backoff timing: 2s, 4s, 6s... gives the server progressive breathing space
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("❌ Thread interrupted during retry sleep for {}.", symbol);
                    return new AdvisoryOiData(null, null, resolvedExpiry);
                }
            }
        }

        // Proceed to parse the JSON only if we successfully retrieved it
        try {
            if (jsonResponse == null || jsonResponse.isEmpty()) {
                log.warn("⚠️ Samco returned empty option chain for {}", symbol);
                return new AdvisoryOiData(null, null, resolvedExpiry);
            }

            SamcoOptionChainResponse response = objectMapper.readValue(jsonResponse, SamcoOptionChainResponse.class);

            if ("Success".equalsIgnoreCase(response.status()) && response.optionChainDetails() != null && !response.optionChainDetails().isEmpty()) {

                // Override blank expiry with the dynamic nearest expiry from the chain
                if (resolvedExpiry == null || resolvedExpiry.isBlank()) {
                    resolvedExpiry = response.optionChainDetails().get(0).expiryDate();
                }

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
                log.error("❌ Samco API Error for {}: {}", symbol, response != null ? response.statusMessage() : "Unknown Error");
            }

        } catch (Exception e) {
            log.error("❌ Failed to parse OI and Greeks for {}: {}", symbol, e.getMessage(), e);
        }

        return new AdvisoryOiData(maxPut, maxCall, resolvedExpiry);
    }

    // 🚀 Helper Methods
    private Double parseDoubleSafely(String value) {
        try {
            return (value == null || value.trim().isEmpty() || "NA".equalsIgnoreCase(value)) ? 0.0 : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private long parseLongSafely(String value) {
        try {
            return (value == null || value.trim().isEmpty() || "NA".equalsIgnoreCase(value)) ? 0L : (long) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}