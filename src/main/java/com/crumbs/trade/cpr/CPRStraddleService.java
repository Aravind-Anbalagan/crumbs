package com.crumbs.trade.cpr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.StraddlePremiumDto;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.OrderService;
import com.crumbs.trade.service.StraddleIntradayService;
import com.crumbs.trade.utility.AppConstant;
import com.crumbs.trade.repo.CPRRepo;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class CPRStraddleService {

    private static final Logger logger = LoggerFactory.getLogger(CPRStraddleService.class);

    // =========================================================================
    // CONSTANTS
    // =========================================================================
    // 65% SL per leg — either leg hits SL -> exit BOTH legs -> no more trades today
    // Why 65%: on big candle days IV is elevated; 65% gives enough room for
    // mean reversion while ensuring the opposite leg has profited ~50%+
    // Net result on SL day = breakeven (65% loss one leg, 50%+ profit other)
    private static final BigDecimal SL_MULTIPLIER = new BigDecimal("1.65");

    // =========================================================================
    // STATE  (reset at 09:00 AM daily via resetDailyFlags())
    // =========================================================================
    private final AtomicBoolean straddlePlaced = new AtomicBoolean(false);
    private final AtomicBoolean ceSLHit        = new AtomicBoolean(false);
    private final AtomicBoolean peSLHit        = new AtomicBoolean(false);
    private final AtomicBoolean ceLegPlaced    = new AtomicBoolean(false); // CE order confirmed in market
    private final AtomicBoolean peLegPlaced    = new AtomicBoolean(false); // PE order confirmed in market

    private BigDecimal         ceEntryPremium  = null;
    private BigDecimal         peEntryPremium  = null;
    private BigDecimal         ceSLPrice       = null; // fixed at 09:20, never changes
    private BigDecimal         peSLPrice       = null; // fixed at 09:20, never changes

    // ATM dto held in memory - tokens resolved once at 09:20,
    // reused every minute for SL price re-fetch (no token lookup overhead)
    private StraddlePremiumDto atmDto          = null;

    // =========================================================================
    // AUTOWIRED
    // =========================================================================
    @Autowired AngelOne                angelOne;
    @Autowired StrategyRepo            strategyRepo;
    @Autowired OrderService            orderService;
    @Autowired StraddleIntradayService straddleIntradayService; // reused as-is, zero changes
    @Autowired CPRRepo                 cprRepo;

    // =========================================================================
    // STEP 1 - PLACE STRADDLE  (called once at 09:20 when big candle detected)
    //
    // Uses StraddleIntradayService:
    //   getATMStrike()                  -> nearest 50 to LTP
    //   buildStraddleDtos()             -> creates DTO list
    //   getAllTokenDetails()             -> resolves CE/PE tokens from Indexes table
    //   getPriceForAllTheStrikesBatch() -> live LTP via batch API
    // =========================================================================
    public void placeStraddle(SmartConnect sc, BigDecimal ltp) {
        if (straddlePlaced.get()) {
            logger.warn("Straddle already placed today - skipping duplicate call.");
            return;
        }

        try {
            Strategy optionStrategy = strategyRepo.findByName("NIFTY");
            if (optionStrategy == null) {
                logger.error("Strategy not found - cannot place straddle.");
                return;
            }

            // ATM strike (nearest 50)
            BigDecimal atmStrike = straddleIntradayService.getATMStrike(
                    AppConstant.CPR_STRATEGY, optionStrategy, ltp);
            logger.info("LTP={} -> ATM Strike={}", ltp, atmStrike);

            // Build strike list and filter to ATM only
            // buildStraddleDtos() returns +/-range; we only need the ATM row
            List<StraddlePremiumDto> strikeList =
                    straddleIntradayService.buildStraddleDtos(atmStrike, 50)
                            .stream()
                            .filter(dto -> dto.getStrikePrice().compareTo(atmStrike) == 0)
                            .collect(Collectors.toList());

            if (strikeList.isEmpty()) {
                logger.error("ATM strike {} missing from list - aborting.", atmStrike);
                return;
            }

            // Resolve CE + PE tokens from Indexes table
            // Symbol format: {name}{expiry}{strike}CE / PE
            // e.g. NIFTY25MAR2323300CE - same as existing straddle logic
            strikeList = straddleIntradayService.getAllTokenDetails(strikeList, optionStrategy);
            StraddlePremiumDto candidate = strikeList.get(0);

            if (candidate.getCeToken() == null || candidate.getPeToken() == null) {
                logger.error("CE or PE token not resolved for ATM={} - aborting.", atmStrike);
                return; // atmDto stays null -> monitorStraddleSL skips cleanly
            }

            // Log tokens before fetch
            logger.info("CE token={} | PE token={}",
                    candidate.getCeToken().getToken(), candidate.getPeToken().getToken());

            // Fetch live premiums — strikeList prices populated here
            strikeList = straddleIntradayService.getPriceForAllTheStrikesBatch(
                    strikeList, sc, optionStrategy.getExchange());

            // Assign atmDto AFTER batch fetch — so getCePrice()/getPePrice() are populated
            atmDto = strikeList.get(0);

            ceEntryPremium = atmDto.getCePrice();
            peEntryPremium = atmDto.getPePrice();

            if (isInvalid(ceEntryPremium) || isInvalid(peEntryPremium)) {
                logger.error("Invalid premiums - CE={} PE={} - aborting.",
                        ceEntryPremium, peEntryPremium);
                return;
            }

            // Calculate 65% SL per leg
            // stored in memory — fixed at 09:20, reused every monitor tick
            ceSLPrice = ceEntryPremium.multiply(SL_MULTIPLIER).setScale(2, RoundingMode.HALF_UP);
            peSLPrice = peEntryPremium.multiply(SL_MULTIPLIER).setScale(2, RoundingMode.HALF_UP);

            logger.info("ATM={} | CE entry={} SL={} | PE entry={} SL={}",
                    atmStrike, ceEntryPremium, ceSLPrice, peEntryPremium, peSLPrice);

            // Place CE leg using pre-resolved token
            // straddlePlaced = true ONLY when BOTH legs confirmed in market
            try {
                Token ceToken = new Token();
                ceToken.setToken(atmDto.getCeToken().getToken());
                ceToken.setSymbol(atmDto.getCeToken().getSymbol());
                ceToken.setExch_seg(atmDto.getCeToken().getExch_seg());
                ceToken.setQuantity(atmDto.getCeToken().getQuantity());

                orderService.orderPlaceWithToken(ceToken, AppConstant.CPR_STRATEGY, "SELL", true);
                ceLegPlaced.set(true);
                logger.info("CE leg placed -> {}", ceToken.getSymbol());
            } catch (Exception | SmartAPIException e) {
                logger.error("CE leg order failed.", e);
            }

            // Place PE leg using pre-resolved token
            try {
                Token peToken = new Token();
                peToken.setToken(atmDto.getPeToken().getToken());
                peToken.setSymbol(atmDto.getPeToken().getSymbol());
                peToken.setExch_seg(atmDto.getPeToken().getExch_seg());
                peToken.setQuantity(atmDto.getPeToken().getQuantity());

                orderService.orderPlaceWithToken(peToken, AppConstant.CPR_STRATEGY, "SELL", true);
                peLegPlaced.set(true);
                logger.info("PE leg placed -> {}", peToken.getSymbol());
            } catch (Exception | SmartAPIException e) {
                logger.error("PE leg order failed.", e);
            }

            if (ceLegPlaced.get() && peLegPlaced.get()) {
                straddlePlaced.set(true);
                logger.info("CPR Straddle active - both legs confirmed.");
                saveStraddleToDB(atmStrike); // persist straddle details to DB
            } else {
                logger.error("Straddle incomplete - CE={} PE={} - straddlePlaced=false. Manual check required.",
                        ceLegPlaced.get(), peLegPlaced.get());
            }

        } catch (Exception e) {
            logger.error("Error placing CPR straddle", e);
        }
    }

    // =========================================================================
    // STEP 2 - MONITOR SL  (called every 1 min via existing CPR scheduler tick)
    //
    // Option B: either leg hits 65% SL -> exit BOTH legs -> no more trades today
    // NOTE: No straddlePlaced guard here - StrategyService.skipCPRTakeStraddle
    //       is the gate. This method is only called on big candle days.
    // =========================================================================
    public void monitorStraddleSL() {
        // straddlePlaced = true ONLY when both legs confirmed in market
        // if placeStraddle() failed for any reason this stays false -> skip safely
        if (!straddlePlaced.get()) {
            logger.warn("Straddle not placed - skipping monitor.");
            return;
        }

        if (ceSLHit.get() && peSLHit.get()) {
            logger.debug("Both legs already closed - nothing to monitor.");
            return;
        }

        try {
            // ── SL prices are in-memory (set once at 09:20, never change) ─
            // No DB read needed every tick — zero overhead
            if (isInvalid(ceSLPrice) || isInvalid(peSLPrice)) {
                logger.error("SL prices not set in memory - skipping. ceSL={} peSL={}", ceSLPrice, peSLPrice);
                return;
            }

            // ── Fetch live CE + PE prices ──────────────────────────────────
            SmartConnect sc             = angelOne.signIn();
            Strategy     optionStrategy = strategyRepo.findByName(AppConstant.CPR_STRATEGY);

            if (optionStrategy == null) {
                logger.warn("Option strategy not found - skipping.");
                return;
            }

            straddleIntradayService.getPriceForAllTheStrikesBatch(
                    Collections.singletonList(atmDto), sc, optionStrategy.getExchange());

            BigDecimal ceCurrent = atmDto.getCePrice();
            BigDecimal peCurrent = atmDto.getPePrice();

            logger.info("SL Monitor | CE current={} / SL={} | PE current={} / SL={}",
                    ceCurrent, ceSLPrice, peCurrent, peSLPrice);

            // ── CE leg SL check — compare live price vs in-memory SL ──────
            if (ceLegPlaced.get() && !ceSLHit.get() && ceCurrent != null
                    && ceCurrent.compareTo(ceSLPrice) >= 0) {
                logger.info("CE SL Hit! current={} >= SL={} - exiting BOTH legs.", ceCurrent, ceSLPrice);
                ceSLHit.set(true);
                exitBothLegs("CE SL hit");
                return; // no more monitoring today
            }

            // ── PE leg SL check — compare live price vs in-memory SL ──────
            if (peLegPlaced.get() && !peSLHit.get() && peCurrent != null
                    && peCurrent.compareTo(peSLPrice) >= 0) {
                logger.info("PE SL Hit! current={} >= SL={} - exiting BOTH legs.", peCurrent, peSLPrice);
                peSLHit.set(true);
                exitBothLegs("PE SL hit");
                return; // no more monitoring today
            }

        } catch (Exception e) {
            logger.error("Error monitoring straddle SL", e);
        }
    }

    // =========================================================================
    // STEP 3 - EOD EXIT  (called at 15:20 via exitAllCPRPositions())
    // Exits only legs that are still open (SL not already hit)
    // NOTE: No straddlePlaced guard here - StrategyService.skipCPRTakeStraddle
    //       is the gate. This method is only called on big candle days.
    // =========================================================================
    public void exitAllStraddlePositions() {
        try {
            logger.info("EOD Straddle exit triggered.");

            if (ceLegPlaced.get() && !ceSLHit.get()) {
                orderService.exitActiveTradeByToken(
                        atmDto.getCeToken().getToken(), AppConstant.CPR_STRATEGY);
                logger.info("CE leg EOD exit done.");
            } else if (!ceLegPlaced.get()) {
                logger.info("CE leg was never placed - skipping exit.");
            } else {
                logger.info("CE leg already closed by SL - skipping.");
            }

            if (peLegPlaced.get() && !peSLHit.get()) {
                orderService.exitActiveTradeByToken(
                        atmDto.getPeToken().getToken(), AppConstant.CPR_STRATEGY);
                logger.info("PE leg EOD exit done.");
            } else if (!peLegPlaced.get()) {
                logger.info("PE leg was never placed - skipping exit.");
            } else {
                logger.info("PE leg already closed by SL - skipping.");
            }

        } catch (Exception | SmartAPIException e) {
            logger.error("Error during EOD straddle exit", e);
        }
    }

    // =========================================================================
    // EXIT BOTH LEGS  (Option B - either SL hit -> close both -> done for day)
    // =========================================================================
    private void exitBothLegs(String reason) {
        logger.info("{} - closing both legs. No more trades today.", reason);
        try {
            if (ceLegPlaced.get() && !ceSLHit.get()) {
                // exit by token — CE and PE share same strategy name,
                // token is the only unique identifier per leg
                orderService.exitActiveTradeByToken(
                        atmDto.getCeToken().getToken(), AppConstant.CPR_STRATEGY);
                ceSLHit.set(true);
                logger.info("CE leg closed.");
            }
            if (peLegPlaced.get() && !peSLHit.get()) {
                orderService.exitActiveTradeByToken(
                        atmDto.getPeToken().getToken(), AppConstant.CPR_STRATEGY);
                peSLHit.set(true);
                logger.info("PE leg closed.");
            }
            logger.info("Both legs closed - straddle done for the day.");
        } catch (Exception | SmartAPIException e) {
            logger.error("Error closing legs on SL trigger", e);
        }
    }

    // =========================================================================
    // SAVE STRADDLE TO DB
    // Reuses existing CPR table — no schema change needed
    //   pivot  = ATM strike
    //   high   = CE entry premium
    //   low    = PE entry premium
    //   top    = CE SL price  (entry * 1.65)
    //   bottom = PE SL price  (entry * 1.65)
    // =========================================================================
    private void saveStraddleToDB(BigDecimal atmStrike) {
        try {
            com.crumbs.trade.entity.CPR cpr = new com.crumbs.trade.entity.CPR();
            cpr.setName(AppConstant.CPR_STRATEGY);
            cpr.setDate(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            cpr.setPivot(atmStrike);        // ATM strike
            cpr.setHigh(ceEntryPremium);    // CE entry premium
            cpr.setLow(peEntryPremium);     // PE entry premium
            cpr.setTop(ceSLPrice);          // CE SL price
            cpr.setBottom(peSLPrice);       // PE SL price
            cprRepo.save(cpr);
            logger.info("Straddle saved to DB -> ATM={} | CE entry={} SL={} | PE entry={} SL={}",
                    atmStrike, ceEntryPremium, ceSLPrice, peEntryPremium, peSLPrice);
        } catch (Exception e) {
            logger.error("Failed to save straddle to DB", e);
        }
    }

    // =========================================================================
    // DAILY RESET  (called at 09:00 AM via StrategyService.resetDailyFlags())
    // =========================================================================
    public void resetDailyFlags() {
        straddlePlaced.set(false);
        ceSLHit.set(false);
        peSLHit.set(false);
        ceLegPlaced.set(false);
        peLegPlaced.set(false);
        ceEntryPremium = null;
        peEntryPremium = null;
        ceSLPrice      = null;
        peSLPrice      = null;
        atmDto         = null;
        logger.info("CPRStraddleService daily flags reset.");
    }

    // =========================================================================
    // HELPER
    // =========================================================================
    private boolean isInvalid(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0;
    }

    // =========================================================================
    // GETTERS - for debugging / status API
    // =========================================================================
    public boolean isStraddlePlaced()     { return straddlePlaced.get(); }
    public boolean isCeSLHit()            { return ceSLHit.get(); }
    public boolean isPeSLHit()            { return peSLHit.get(); }
    public BigDecimal getCeEntryPremium() { return ceEntryPremium; }
    public BigDecimal getPeEntryPremium() { return peEntryPremium; }
    public BigDecimal getCeSLPrice()      { return ceSLPrice; }
    public BigDecimal getPeSLPrice()      { return peSLPrice; }
}