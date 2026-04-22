package com.crumbs.trade.cpr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import com.crumbs.trade.repo.CPRRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.AngelOneService;
import com.crumbs.trade.service.OrderService;
import com.crumbs.trade.service.StraddleIntradayService;
import com.crumbs.trade.utility.AppConstant;

@Service
public class CPRStraddleService {

    private static final Logger logger = LoggerFactory.getLogger(CPRStraddleService.class);

    // =========================================================================
    // CONSTANTS
    // =========================================================================
    private static final BigDecimal SL_MULTIPLIER         = new BigDecimal("1.65");
    private static final int        BREAKOUT_CONFIRM_TICKS = 5;

    // =========================================================================
    // ATOMIC FLAGS
    // =========================================================================
    private final AtomicBoolean straddlePlaced = new AtomicBoolean(false);
    private final AtomicBoolean ceSLHit        = new AtomicBoolean(false);
    private final AtomicBoolean peSLHit        = new AtomicBoolean(false);
    private final AtomicBoolean ceLegPlaced    = new AtomicBoolean(false);
    private final AtomicBoolean peLegPlaced    = new AtomicBoolean(false);

    // =========================================================================
    // INTRADAY STATE
    //
    // Written once in placeStraddle(), then only read by monitorStraddleSL().
    // volatile is sufficient — the AtomicBoolean guards ensure the monitor
    // never runs before placement completes.
    // =========================================================================
    private volatile BigDecimal ceEntryPremium = null;
    private volatile BigDecimal peEntryPremium = null;
    private volatile BigDecimal ceSLPrice      = null;
    private volatile BigDecimal peSLPrice      = null;
    private volatile BigDecimal first5High     = null;
    private volatile BigDecimal first5Low      = null;

    // Trend-confirm counters — only accessed from the single scheduler thread
    private int ceBreakoutCount  = 0;
    private int peBreakdownCount = 0;

    private volatile StraddlePremiumDto atmDto = null;

    // =========================================================================
    // AUTOWIRED
    // =========================================================================
    @Autowired AngelOne                angelOne;
    @Autowired AngelOneService         angelOneService;
    @Autowired StrategyRepo            strategyRepo;
    @Autowired OrderService            orderService;
    @Autowired StraddleIntradayService straddleIntradayService;
    @Autowired CPRRepo                 cprRepo;

    // =========================================================================
    // STEP 1 — PLACE STRADDLE  (called once at 09:20 on big-candle days)
    // =========================================================================
    public void placeStraddle(SmartConnect sc, BigDecimal ltp,
                              BigDecimal first5High, BigDecimal first5Low) {

        if (straddlePlaced.get()) {
            logger.warn("Straddle already placed today — skipping duplicate call.");
            return;
        }

        try {
            this.first5High = first5High;
            this.first5Low  = first5Low;
            logger.info("5-min levels stored → first5High={} first5Low={}", first5High, first5Low);

            // NOTE: findByName("NIFTY") fetches option strategy config — not an order lookup
            Strategy optionStrategy = strategyRepo.findByName("NIFTY");
            if (optionStrategy == null) {
                logger.error("Strategy NIFTY not found — cannot place straddle.");
                return;
            }

            BigDecimal atmStrike = straddleIntradayService.getATMStrike(
                    AppConstant.CPR_STRATEGY, optionStrategy, ltp);
            logger.info("LTP={} → ATM Strike={}", ltp, atmStrike);

            List<StraddlePremiumDto> strikeList =
                    straddleIntradayService.buildStraddleDtos(atmStrike, 50)
                            .stream()
                            .filter(dto -> dto.getStrikePrice().compareTo(atmStrike) == 0)
                            .collect(Collectors.toList());

            if (strikeList.isEmpty()) {
                logger.error("ATM strike {} missing from list — aborting.", atmStrike);
                return;
            }

            strikeList = straddleIntradayService.getAllTokenDetails(strikeList, optionStrategy);
            StraddlePremiumDto candidate = strikeList.get(0);

            if (candidate.getCeToken() == null || candidate.getPeToken() == null) {
                logger.error("CE or PE token not resolved for ATM={} — aborting.", atmStrike);
                return;
            }

            strikeList = straddleIntradayService.getPriceForAllTheStrikesBatch(
                    strikeList, sc, optionStrategy.getExchange());

            atmDto = strikeList.get(0);

            ceEntryPremium = atmDto.getCePrice();
            peEntryPremium = atmDto.getPePrice();

            if (isInvalid(ceEntryPremium) || isInvalid(peEntryPremium)) {
                logger.error("Invalid premiums — CE={} PE={} — aborting.", ceEntryPremium, peEntryPremium);
                return;
            }

            ceSLPrice = ceEntryPremium.multiply(SL_MULTIPLIER).setScale(2, RoundingMode.HALF_UP);
            peSLPrice = peEntryPremium.multiply(SL_MULTIPLIER).setScale(2, RoundingMode.HALF_UP);

            logger.info("ATM={} | CE entry={} SL={} | PE entry={} SL={}",
                    atmStrike, ceEntryPremium, ceSLPrice, peEntryPremium, peSLPrice);

            // ── Place CE leg ──────────────────────────────────────────────────
            // FIX: CPR_STRADDLE_STRATEGY ("CPR-STRADDLE") isolates straddle orders
            //      from directional CPR orders ("CPR") so findByNameAndActive("CPR")
            //      in StrategyService never returns a straddle leg by mistake.
            try {
                orderService.orderPlaceWithToken(
                        buildToken(atmDto.getCeToken(),atmStrike), AppConstant.CPR_STRADDLE_STRATEGY, "SELL", true);
                ceLegPlaced.set(true);
                logger.info("CE leg placed → {}", atmDto.getCeToken().getSymbol());
            } catch (Exception | SmartAPIException e) {
                logger.error("CE leg order failed.", e);
            }

            // ── Place PE leg ──────────────────────────────────────────────────
            try {
                orderService.orderPlaceWithToken(
                        buildToken(atmDto.getPeToken(),atmStrike), AppConstant.CPR_STRADDLE_STRATEGY, "SELL", true);
                peLegPlaced.set(true);
                logger.info("PE leg placed → {}", atmDto.getPeToken().getSymbol());
            } catch (Exception | SmartAPIException e) {
                logger.error("PE leg order failed.", e);
            }

            if (ceLegPlaced.get() && peLegPlaced.get()) {
                straddlePlaced.set(true);
                logger.info("CPR Straddle active — both legs confirmed.");
                saveStraddleToDB(atmStrike);
            } else {
                logger.error("Straddle incomplete — CE={} PE={} — manual check required.",
                        ceLegPlaced.get(), peLegPlaced.get());
            }

        } catch (Exception e) {
            logger.error("Error placing CPR straddle", e);
        }
    }

    // =========================================================================
    // STEP 2 — MONITOR SL  (called every 1 min, 09:21 → 15:19)
    //
    // TWO SL types per leg:
    //   Premium SL : option price >= entry × 1.65  → exit BOTH legs
    //   Trend SL   : Nifty outside first5H/L for BREAKOUT_CONFIRM_TICKS
    //                consecutive ticks             → exit THAT leg only
    // =========================================================================
    public void monitorStraddleSL() throws SmartAPIException {

        if (!straddlePlaced.get()) {
            logger.warn("Straddle not placed — skipping monitor.");
            return;
        }
        if (ceSLHit.get() && peSLHit.get()) {
            logger.debug("Both legs already closed — nothing to monitor.");
            return;
        }

        try {
            if (isInvalid(ceSLPrice) || isInvalid(peSLPrice)) {
                logger.error("SL prices not set — skipping. ceSL={} peSL={}", ceSLPrice, peSLPrice);
                return;
            }

            SmartConnect sc = angelOne.signIn();

            // NOTE: findByName(CPR_STRATEGY) fetches option strategy config — not an order lookup
            Strategy optionStrategy = strategyRepo.findByName(AppConstant.CPR_STRATEGY);
            if (optionStrategy == null) {
                logger.warn("Option strategy not found — skipping.");
                return;
            }

            List<StraddlePremiumDto> updated = straddleIntradayService.getPriceForAllTheStrikesBatch(
                    Collections.singletonList(atmDto), sc, optionStrategy.getExchange());

            if (updated == null || updated.isEmpty()) {
                logger.error("Batch price fetch returned empty — skipping tick.");
                return;
            }
            atmDto = updated.get(0);

            BigDecimal ceCurrent = atmDto.getCePrice();
            BigDecimal peCurrent = atmDto.getPePrice();

            BigDecimal niftyLtp = angelOneService.getcurrentPrice(
                    sc, optionStrategy.getExchange(),
                    optionStrategy.getTradingsymbol(), optionStrategy.getToken(), "ltp");

            logger.info("SL Monitor | CE={}/{} PE={}/{} | NiftyLTP={} first5H={} first5L={} | ceBreakout={} peBreakdown={}",
                    ceCurrent, ceSLPrice, peCurrent, peSLPrice,
                    niftyLtp, first5High, first5Low,
                    ceBreakoutCount, peBreakdownCount);

            // ── Update trend-confirm counters ─────────────────────────────────
            if (niftyLtp != null && first5High != null && first5Low != null) {
                if (niftyLtp.compareTo(first5High) > 0) {
                    ceBreakoutCount++;
                    peBreakdownCount = 0;
                } else if (niftyLtp.compareTo(first5Low) < 0) {
                    peBreakdownCount++;
                    ceBreakoutCount = 0;
                } else {
                    ceBreakoutCount  = 0;
                    peBreakdownCount = 0;
                }
            }

            // ── CE SL check ───────────────────────────────────────────────────
            if (ceLegPlaced.get() && !ceSLHit.get()) {
                boolean premiumSL = ceCurrent != null && ceCurrent.compareTo(ceSLPrice) >= 0;
                boolean trendSL   = ceBreakoutCount >= BREAKOUT_CONFIRM_TICKS;

                if (premiumSL) {
                    logger.info("CE Premium SL hit! current={} >= SL={} — exiting BOTH legs.", ceCurrent, ceSLPrice);
                    exitBothLegs("CE Premium SL hit");
                    return;
                }
                if (trendSL) {
                    logger.info("CE Trend SL hit! Nifty above first5High={} for {} ticks — exiting CE leg only.",
                            first5High, ceBreakoutCount);
                    // FIX: CPR_STRADDLE_STRATEGY — exits only the straddle CE leg,
                    //      never touches the directional CPR trade
                    orderService.exitActiveTradeByToken(
                            atmDto.getCeToken().getToken(), "NIFTY", AppConstant.CPR_STRADDLE_STRATEGY);
                    ceSLHit.set(true);
                    logger.info("CE leg closed. PE continues.");
                    return;
                }
            }

            // ── PE SL check ───────────────────────────────────────────────────
            if (peLegPlaced.get() && !peSLHit.get()) {
                boolean premiumSL = peCurrent != null && peCurrent.compareTo(peSLPrice) >= 0;
                boolean trendSL   = peBreakdownCount >= BREAKOUT_CONFIRM_TICKS;

                if (premiumSL) {
                    logger.info("PE Premium SL hit! current={} >= SL={} — exiting BOTH legs.", peCurrent, peSLPrice);
                    exitBothLegs("PE Premium SL hit");
                    return;
                }
                if (trendSL) {
                    logger.info("PE Trend SL hit! Nifty below first5Low={} for {} ticks — exiting PE leg only.",
                            first5Low, peBreakdownCount);
                    // FIX: CPR_STRADDLE_STRATEGY — exits only the straddle PE leg,
                    //      never touches the directional CPR trade
                    orderService.exitActiveTradeByToken(
                            atmDto.getPeToken().getToken(), "NIFTY", AppConstant.CPR_STRADDLE_STRATEGY);
                    peSLHit.set(true);
                    logger.info("PE leg closed. CE continues.");
                    return;
                }
            }

        } catch (Exception e) {
            logger.error("Error monitoring straddle SL", e);
        }
    }

    // =========================================================================
    // STEP 3 — EOD EXIT  (called at 15:20)
    // =========================================================================
    public void exitAllStraddlePositions() {
        try {
            logger.info("EOD Straddle exit triggered.");

            if (ceLegPlaced.get() && !ceSLHit.get()) {
                // FIX: CPR_STRADDLE_STRATEGY — scoped exit, directional trade unaffected
            	orderService.exitActiveTradeByToken(
                        atmDto.getCeToken().getToken(), "NIFTY", AppConstant.CPR_STRADDLE_STRATEGY);
                logger.info("CE leg EOD exit done.");
            } else if (!ceLegPlaced.get()) {
                logger.info("CE leg was never placed — skipping.");
            } else {
                logger.info("CE leg already closed by SL — skipping.");
            }

            if (peLegPlaced.get() && !peSLHit.get()) {
                // FIX: CPR_STRADDLE_STRATEGY — scoped exit, directional trade unaffected
            	orderService.exitActiveTradeByToken(
                        atmDto.getPeToken().getToken(), "NIFTY", AppConstant.CPR_STRADDLE_STRATEGY);
                logger.info("PE leg EOD exit done.");
            } else if (!peLegPlaced.get()) {
                logger.info("PE leg was never placed — skipping.");
            } else {
                logger.info("PE leg already closed by SL — skipping.");
            }

        } catch (Exception | SmartAPIException e) {
            logger.error("Error during EOD straddle exit", e);
        }
    }

    // =========================================================================
    // EXIT BOTH LEGS
    // =========================================================================
    private void exitBothLegs(String reason) {
        logger.info("{} — closing both legs.", reason);
        try {
            if (ceLegPlaced.get() && !ceSLHit.get()) {
                // FIX: CPR_STRADDLE_STRATEGY — scoped exit, directional trade unaffected
            	orderService.exitActiveTradeByToken(
                        atmDto.getCeToken().getToken(), "NIFTY", AppConstant.CPR_STRADDLE_STRATEGY);
                ceSLHit.set(true);
                logger.info("CE leg closed.");
            }
            if (peLegPlaced.get() && !peSLHit.get()) {
                // FIX: CPR_STRADDLE_STRATEGY — scoped exit, directional trade unaffected
            	orderService.exitActiveTradeByToken(
                        atmDto.getPeToken().getToken(), "NIFTY", AppConstant.CPR_STRADDLE_STRATEGY);
                peSLHit.set(true);
                logger.info("PE leg closed.");
            }
        } catch (Exception | SmartAPIException e) {
            logger.error("Error closing legs on SL trigger", e);
        }
    }

    // =========================================================================
    // SAVE TO DB
    //
    // Same CPR table, distinct name = CPR_STRADDLE_STRATEGY ("CPR-STRADDLE").
    // getCPRStrategySignal() calls cprRepo.findByName(CPR_STRATEGY) so it
    // never picks up this row.
    //
    // Column mapping:
    //   pivot  → ATM strike
    //   high   → CE entry premium
    //   top    → CE SL price  (entry × 1.65)
    //   low    → PE entry premium
    //   bottom → PE SL price  (entry × 1.65)
    // =========================================================================
    private void saveStraddleToDB(BigDecimal atmStrike) {
        try {
            com.crumbs.trade.entity.CPR cpr = new com.crumbs.trade.entity.CPR();
            cpr.setName(AppConstant.CPR_STRADDLE_STRATEGY);
            cpr.setDate(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            cpr.setPivot(atmStrike);
            cpr.setHigh(ceEntryPremium);
            cpr.setTop(ceSLPrice);
            cpr.setLow(peEntryPremium);
            cpr.setBottom(peSLPrice);

            cprRepo.save(cpr);

            logger.info("Straddle saved [name={}] → ATM={} | CE entry={} SL={} | PE entry={} SL={}",
                    AppConstant.CPR_STRADDLE_STRATEGY,
                    atmStrike, ceEntryPremium, ceSLPrice, peEntryPremium, peSLPrice);

        } catch (Exception e) {
            logger.error("Failed to save straddle to DB", e);
        }
    }

    // =========================================================================
    // DAILY RESET  (called at 09:00 AM)
    // =========================================================================
    public void resetDailyFlags() {
        straddlePlaced.set(false);
        ceSLHit.set(false);
        peSLHit.set(false);
        ceLegPlaced.set(false);
        peLegPlaced.set(false);
        ceEntryPremium   = null;
        peEntryPremium   = null;
        ceSLPrice        = null;
        peSLPrice        = null;
        first5High       = null;
        first5Low        = null;
        ceBreakoutCount  = 0;
        peBreakdownCount = 0;
        atmDto           = null;
        logger.info("CPRStraddleService daily flags reset.");
    }

    // =========================================================================
    // HELPERS
    // =========================================================================
    private boolean isInvalid(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0;
    }

    /** Copies token fields into a fresh Token DTO to avoid mutating shared state */
    private Token buildToken(Token source,BigDecimal strike) {
        Token t = new Token();
        t.setToken(source.getToken());
        t.setSymbol(source.getSymbol());
        t.setExch_seg(source.getExch_seg());
        t.setQuantity(source.getQuantity());
        t.setStrike(strike);
        return t;
    }

    // =========================================================================
    // GETTERS
    // =========================================================================
    public boolean    isStraddlePlaced()     { return straddlePlaced.get(); }
    public boolean    isCeSLHit()            { return ceSLHit.get(); }
    public boolean    isPeSLHit()            { return peSLHit.get(); }
    public BigDecimal getCeEntryPremium()    { return ceEntryPremium; }
    public BigDecimal getPeEntryPremium()    { return peEntryPremium; }
    public BigDecimal getCeSLPrice()         { return ceSLPrice; }
    public BigDecimal getPeSLPrice()         { return peSLPrice; }
}