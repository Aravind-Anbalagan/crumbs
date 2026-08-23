package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.repo.IndexesRepo;

import lombok.RequiredArgsConstructor;

/**
 * Standalone, reusable service for resolving an underlying's ATM option
 * contracts and deriving simple option-premium signals from their live
 * prices — e.g. Straddle-vs-Strangle.
 *
 * Named generically (not "OptionStrategyService") because the core piece —
 * {@link #resolveAtmContracts(String, BigDecimal)} — just answers "what are
 * this stock's ATM CE/PE tokens right now?" and is useful to ANY strategy
 * that needs option tokens, not only the straddle/strangle check currently
 * built on top of it. Add new strategy methods here rather than duplicating
 * the token-resolution logic elsewhere.
 *
 * DESIGN CONTRACT — read before wiring this into any scheduler/alert:
 *   - This service NEVER throws. Every public method catches all exceptions
 *     internally and returns a safe "Err" / empty result instead.
 *   - Callers (e.g. FnoScannerService) can therefore call this inline inside
 *     an existing alert loop without any try/catch of their own, and without
 *     any risk of it blocking or breaking that alert.
 *   - Not F&O-scanner-specific: any other strategy that needs "give me the
 *     ATM CE/PE contracts and premiums for this stock" can reuse
 *     {@link #resolveAtmContracts(String, BigDecimal)} or
 *     {@link #determineStraddleOrStrangle(SmartConnect, String, BigDecimal)}
 *     directly.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final Logger logger = LogManager.getLogger(TokenService.class);

    // ──────────────────────────────────────────────────────────
    //  ⚙️ CONFIGURABLE THRESHOLDS
    // ──────────────────────────────────────────────────────────
    // 🔧 Confirmed: this system only trades NSE, so options are NFO only.
    // (BFO/BSE data was seen in an earlier sample but does not apply here —
    // do not use it.)
    private static final List<String> OPTIONS_EXCHANGES = List.of("NFO");
    // CE and PE LTP within this % of each other ⇒ Straddle. Beyond it ⇒ Strangle.
    // NOTE: no explicit threshold was confirmed — this is a reasonable default,
    // tune freely.
    private static final BigDecimal STRADDLE_EQUALITY_THRESHOLD_PCT = new BigDecimal("10");

    public static final String TYPE_STRADDLE = "Straddle";
    public static final String TYPE_STRANGLE = "Strangle";
    public static final String TYPE_ERROR = "Err";

    // 🔧 Confirmed from real data: the `strike` column stores the actual
    // strike price multiplied by 100 (e.g. TCS26SEP2000PE, a ₹2000 strike,
    // has strike = "200000.000000" in the table). Always divide by this.
    private static final BigDecimal STRIKE_SCALE_FACTOR = new BigDecimal("100");

    // Candidate formats seen across different broker/instrument-master exports.
    // Built case-insensitive since real data uses upper-case month abbreviations
    // (e.g. "24SEP2026") which Java's default parser would otherwise reject.
    // Parsing tries each in order; unrecognized values are simply skipped
    // rather than failing the whole lookup.
    private static final List<DateTimeFormatter> EXPIRY_FORMATS = List.of(
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("ddMMMyyyy").toFormatter(),   // 24SEP2026
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(), // 24-SEP-2026
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("ddMMMyy").toFormatter(),     // 24SEP26
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("yyyyMMdd").toFormatter()     // 20260924
    );

    private final IndexesRepo indexesRepo;
    private final PredictionService predictionService;

    // ──────────────────────────────────────────────────────────
    //  Public API — safe to call from any alerting/strategy path
    // ──────────────────────────────────────────────────────────

    /**
     * Resolves the ATM Call + Put contracts for {@code stockName}, nearest
     * upcoming expiry, nearest strike to {@code spotLtp}. Returns
     * {@link Optional#empty()} on ANY failure (no expiry found, no strikes,
     * parse error, etc) — never throws.
     */
    public Optional<AtmContracts> resolveAtmContracts(String stockName, BigDecimal spotLtp) {
        try {
            if (stockName == null || stockName.isBlank() || spotLtp == null || spotLtp.compareTo(BigDecimal.ZERO) <= 0) {
                return Optional.empty();
            }

            String nearestExpiry = resolveNearestExpiry(stockName);
            if (nearestExpiry == null) {
                logger.warn("⚠️ No resolvable expiry found for {}", stockName);
                return Optional.empty();
            }

            List<Indexes> contracts = indexesRepo.findByNameAndExchangeInAndExpiryOrderByStrikeAsc(
                    stockName, OPTIONS_EXCHANGES, nearestExpiry);

            if (contracts == null || contracts.isEmpty()) {
                logger.warn("⚠️ No option contracts found for {} expiry {}", stockName, nearestExpiry);
                return Optional.empty();
            }

            // Group by strike, keeping whichever CE/PE symbol we find at each strike.
            Map<BigDecimal, Indexes> ceByStrike = new LinkedHashMap<>();
            Map<BigDecimal, Indexes> peByStrike = new LinkedHashMap<>();

            for (Indexes contract : contracts) {
                BigDecimal strike = parseStrike(contract.getStrike());
                String symbol = contract.getSymbol();
                if (strike == null || symbol == null) {
                    continue;
                }
                String upperSymbol = symbol.trim().toUpperCase();
                if (upperSymbol.endsWith("CE")) {
                    ceByStrike.putIfAbsent(strike, contract);
                } else if (upperSymbol.endsWith("PE")) {
                    peByStrike.putIfAbsent(strike, contract);
                }
            }

            // Find the strike nearest to spot that has BOTH a CE and PE contract
            // on the SAME exchange (a single marketData() batch call requires
            // one exchange — don't pair a BFO call with an NFO put).
            BigDecimal bestStrike = null;
            BigDecimal bestDistance = null;
            for (BigDecimal strike : ceByStrike.keySet()) {
                Indexes peCandidate = peByStrike.get(strike);
                Indexes ceCandidate = ceByStrike.get(strike);
                if (peCandidate == null
                        || ceCandidate.getExchange() == null
                        || !ceCandidate.getExchange().equalsIgnoreCase(peCandidate.getExchange())) {
                    continue;
                }
                BigDecimal distance = strike.subtract(spotLtp).abs();
                if (bestDistance == null || distance.compareTo(bestDistance) < 0) {
                    bestDistance = distance;
                    bestStrike = strike;
                }
            }

            if (bestStrike == null) {
                logger.warn("⚠️ No strike with both CE and PE (same exchange) found for {} expiry {}", stockName, nearestExpiry);
                return Optional.empty();
            }

            Indexes ceContract = ceByStrike.get(bestStrike);
            Indexes peContract = peByStrike.get(bestStrike);

            if (ceContract.getToken() == null || peContract.getToken() == null) {
                logger.warn("⚠️ ATM strike {} found for {} but token missing on CE/PE row", bestStrike, stockName);
                return Optional.empty();
            }

            return Optional.of(new AtmContracts(
                    stockName, nearestExpiry, bestStrike,
                    ceContract.getToken(), peContract.getToken(), ceContract.getExchange()));

        } catch (Exception e) {
            logger.warn("⚠️ resolveAtmContracts failed for {} — Reason: {}", stockName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Full pipeline: resolve ATM CE/PE contracts, fetch their live LTP via the
     * broker's marketData() API, and classify as Straddle/Strangle based on
     * how close the two premiums are. Returns {@link #TYPE_ERROR} on any
     * failure at any stage — this method is guaranteed not to throw and is
     * safe to call inline inside an existing alert loop.
     *
     * @param smartConnect an already-signed-in broker session (caller manages
     *                      sign-in/reuse — this method does not sign in itself)
     * @param stockName     underlying F&O stock name, matching Indexes.name
     * @param spotLtp       current spot LTP of the underlying (used to pick ATM strike)
     */
    public String determineStraddleOrStrangle(SmartConnect smartConnect, String stockName, BigDecimal spotLtp) {
        try {
            if (smartConnect == null) {
                logger.warn("⚠️ determineStraddleOrStrangle called with null SmartConnect for {}", stockName);
                return TYPE_ERROR;
            }

            Optional<AtmContracts> atmOpt = resolveAtmContracts(stockName, spotLtp);
            if (atmOpt.isEmpty()) {
                return TYPE_ERROR;
            }
            AtmContracts atm = atmOpt.get();

            JSONObject payload = predictionService.buildMarketDataPayload(
                    List.of(atm.ceToken(), atm.peToken()), atm.exchange());
            JSONObject response = predictionService.callMarketDataWithRetry(smartConnect, payload);

            if (response == null) {
                logger.warn("⚠️ Null market data response for {} ATM options", stockName);
                return TYPE_ERROR;
            }

            Map<String, BigDecimal> ltpByToken = extractLtpByToken(response);
            BigDecimal ceLtp = ltpByToken.get(atm.ceToken());
            BigDecimal peLtp = ltpByToken.get(atm.peToken());

            if (ceLtp == null || peLtp == null
                    || ceLtp.compareTo(BigDecimal.ZERO) <= 0 || peLtp.compareTo(BigDecimal.ZERO) <= 0) {
                logger.warn("⚠️ Missing/zero CE or PE LTP for {} ATM strike {}", stockName, atm.strike());
                return TYPE_ERROR;
            }

            BigDecimal higher = ceLtp.max(peLtp);
            BigDecimal diffPct = ceLtp.subtract(peLtp).abs()
                    .divide(higher, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));

            return diffPct.compareTo(STRADDLE_EQUALITY_THRESHOLD_PCT) <= 0 ? TYPE_STRADDLE : TYPE_STRANGLE;

        } catch (Exception | SmartAPIException e) {
            logger.warn("⚠️ determineStraddleOrStrangle failed for {} — Reason: {}", stockName, e.getMessage());
            return TYPE_ERROR;
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────

    /**
     * Finds the soonest expiry (>= today) among all expiry strings stored for
     * this stock on NFO, trying several known date formats. Returns
     * null if nothing parses or nothing is upcoming.
     */
    private String resolveNearestExpiry(String stockName) {
        List<String> rawExpiries = indexesRepo.findDistinctExpiriesByNameAndExchangeIn(stockName, OPTIONS_EXCHANGES);
        if (rawExpiries == null || rawExpiries.isEmpty()) {
            return null;
        }

        LocalDate today = LocalDate.now();
        String bestRaw = null;
        LocalDate bestDate = null;

        for (String raw : rawExpiries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            LocalDate parsed = tryParseExpiry(raw.trim());
            if (parsed == null || parsed.isBefore(today)) {
                continue;
            }
            if (bestDate == null || parsed.isBefore(bestDate)) {
                bestDate = parsed;
                bestRaw = raw;
            }
        }

        return bestRaw;
    }

    private LocalDate tryParseExpiry(String raw) {
        for (DateTimeFormatter fmt : EXPIRY_FORMATS) {
            try {
                return LocalDate.parse(raw.toUpperCase(), fmt);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        return null;
    }

    /**
     * Parses the raw `strike` column and scales it down by
     * {@link #STRIKE_SCALE_FACTOR}. Confirmed from real data: the column
     * stores strike * 100 (e.g. a ₹2000 strike is stored as "200000.000000").
     */
    private BigDecimal parseStrike(String rawStrike) {
        if (rawStrike == null || rawStrike.isBlank()) {
            return null;
        }
        try {
            BigDecimal raw = new BigDecimal(rawStrike.trim());
            return raw.divide(STRIKE_SCALE_FACTOR, 2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses the same {data.fetched[]} shape used elsewhere in this codebase
     * (see FnoScannerService.extractFetchedArray) into a token -> LTP map.
     */
    private Map<String, BigDecimal> extractLtpByToken(JSONObject response) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        try {
            JSONArray fetched = null;
            if (response.has("data") && !response.isNull("data")) {
                JSONObject data = response.getJSONObject("data");
                if (data.has("fetched")) {
                    fetched = data.getJSONArray("fetched");
                }
            } else if (response.has("fetched")) {
                fetched = response.getJSONArray("fetched");
            }

            if (fetched == null) {
                return result;
            }

            for (int i = 0; i < fetched.length(); i++) {
                JSONObject item = fetched.getJSONObject(i);
                if (!item.has("symbolToken") || !item.has("ltp") || item.isNull("ltp")) {
                    continue;
                }
                String token = item.get("symbolToken").toString();
                BigDecimal ltp = new BigDecimal(item.get("ltp").toString());
                result.put(token, ltp);
            }
        } catch (Exception e) {
            logger.warn("⚠️ Failed parsing option market data response: {}", e.getMessage());
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────
    //  Public result type — reusable by any other strategy
    // ──────────────────────────────────────────────────────────

    /**
     * ATM Call + Put contract identity for a resolved underlying/expiry/strike.
     * Deliberately holds only tokens + identifying info, not live prices —
     * callers fetch LTP/depth themselves via whatever API shape they need.
     */
    public record AtmContracts(
            String underlying,
            String expiry,
            BigDecimal strike,
            String ceToken,
            String peToken,
            String exchange
    ) {}
}