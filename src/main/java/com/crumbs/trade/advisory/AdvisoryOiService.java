package com.crumbs.trade.advisory;

import com.crumbs.trade.broker.Samco;
import com.crumbs.trade.dto.SamcoOptionChainResponse;
import com.crumbs.trade.utility.SamcoSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdvisoryOiService {

    private final Samco samco;
    private final SamcoSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    // 🚀 NON-BLOCKING THROTTLE: Tracks the absolute time the next API call is allowed
    private static final AtomicLong NEXT_ALLOWED_API_TIME = new AtomicLong(0);
    private static final long RATE_LIMIT_DELAY_MS = 1200;

    public record StrikeDetails(BigDecimal strike, long openInterest, BigDecimal ltp, double delta, double theta, double iv) {}
    public record AdvisoryOiData(StrikeDetails putWall, StrikeDetails callWall, String expiry) {}

    public AdvisoryOiData fetchLiveOiAndGreeks(String symbol, String exchange, String expiry) {
        StrikeDetails maxPut = null;
        StrikeDetails maxCall = null;
        long maxPutOi = 0L;
        long maxCallOi = 0L;
        String resolvedExpiry = expiry;
        String jsonResponse = null;

        int maxRetries = 4;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // 1. Calculate how long THIS thread needs to sleep, and instantly update the tracker for the NEXT thread
                long now = System.currentTimeMillis();
                long executionTime = NEXT_ALLOWED_API_TIME.updateAndGet(lastTime -> Math.max(lastTime + RATE_LIMIT_DELAY_MS, now));
                long sleepTime = executionTime - System.currentTimeMillis();

                // 2. Sleep outside of any locks. Threads wait in parallel.
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }

                String sessionToken = sessionManager.getSession();
                jsonResponse = samco.getOptionChain(sessionToken, exchange, symbol, expiry, null, null);

                break; // API call succeeded

            } catch (Exception e) {
                boolean isRateLimit = e.getMessage() != null && e.getMessage().contains("429");

                if (isRateLimit) {
                    log.warn("⏳ [Attempt {}/{}] Rate Limit (429) hit for {}. Breathing...", attempt, maxRetries, symbol);
                } else {
                    log.warn("⏳ [Attempt {}/{}] Fetch failed for {}: {}. Breathing...", attempt, maxRetries, symbol, e.getMessage());
                }

                if (attempt == maxRetries) {
                    log.error("❌ Exhausted all {} retries for {}. Skipping OI fetch.", maxRetries, symbol);
                    return new AdvisoryOiData(null, null, resolvedExpiry);
                }

                try {
                    // Exponential backoff outside of the throttle lock
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new AdvisoryOiData(null, null, resolvedExpiry);
                }
            }
        }

        // Proceed to parse JSON... (Keep your existing JSON parsing logic exactly as it was)
        try {
            if (jsonResponse == null || jsonResponse.isEmpty()) {
                return new AdvisoryOiData(null, null, resolvedExpiry);
            }

            SamcoOptionChainResponse response = objectMapper.readValue(jsonResponse, SamcoOptionChainResponse.class);
            if ("Success".equalsIgnoreCase(response.status()) && response.optionChainDetails() != null && !response.optionChainDetails().isEmpty()) {
                if (resolvedExpiry == null || resolvedExpiry.isBlank()) {
                    resolvedExpiry = response.optionChainDetails().get(0).expiryDate();
                }

                final String targetExpiry = resolvedExpiry;
                var currentExpiryDetails = response.optionChainDetails().stream()
                        .filter(detail -> targetExpiry.equalsIgnoreCase(detail.expiryDate()))
                        .toList();

                for (var detail : currentExpiryDetails) {
                    long currentOi = parseLongSafely(detail.openInterest());
                    BigDecimal currentStrike = new BigDecimal(detail.strikePrice());
                    BigDecimal ltp = new BigDecimal(detail.lastTradedPrice() != null ? detail.lastTradedPrice() : "0");
                    double delta = parseDoubleSafely(detail.delta());
                    double theta = parseDoubleSafely(detail.theta());
                    double iv = parseDoubleSafely(detail.impliedVolatility());

                    if ("PE".equalsIgnoreCase(detail.optionType()) && currentOi > maxPutOi) {
                        maxPutOi = currentOi;
                        maxPut = new StrikeDetails(currentStrike, currentOi, ltp, delta, theta, iv);
                    } else if ("CE".equalsIgnoreCase(detail.optionType()) && currentOi > maxCallOi) {
                        maxCallOi = currentOi;
                        maxCall = new StrikeDetails(currentStrike, currentOi, ltp, delta, theta, iv);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Failed to parse OI and Greeks for {}: {}", symbol, e.getMessage(), e);
        }

        return new AdvisoryOiData(maxPut, maxCall, resolvedExpiry);
    }

    private Double parseDoubleSafely(String value) {
        try { return (value == null || value.trim().isEmpty() || "NA".equalsIgnoreCase(value)) ? 0.0 : Double.parseDouble(value); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private long parseLongSafely(String value) {
        try { return (value == null || value.trim().isEmpty() || "NA".equalsIgnoreCase(value)) ? 0L : (long) Double.parseDouble(value); }
        catch (NumberFormatException e) { return 0L; }
    }
}