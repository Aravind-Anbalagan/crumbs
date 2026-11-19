package com.crumbs.trade.advisory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpreadSuggestionService {

    private static final BigDecimal MAX_ALLOWED_LOSS = new BigDecimal("6000");
    private final ObjectMapper objectMapper;

    /**
     * Builds a JSON spread suggestion for a NEW_ENTRY signal.
     * Completely fault-tolerant: returns null on any error or if no strike fits <= ₹6,000 max loss.
     */
    public String buildSpreadSuggestionJson(
            String optionType,
            AdvisoryOiService.StrikeDetails sellWall,
            List<AdvisoryOiService.StrikeDetails> sameTypeStrikes,
            int lotSize,
            String expiry) {

        try {
            if (sellWall == null || sameTypeStrikes == null || sameTypeStrikes.isEmpty() || lotSize <= 0) {
                return null;
            }

            boolean isPe = "PE".equalsIgnoreCase(optionType);
            BigDecimal lotSizeBd = BigDecimal.valueOf(lotSize);

            SpreadSuggestionDto bestSpread = sameTypeStrikes.stream()
                    // PE spread: buy lower strike | CE spread: buy higher strike
                    .filter(buy -> isPe
                            ? buy.strike().compareTo(sellWall.strike()) < 0
                            : buy.strike().compareTo(sellWall.strike()) > 0)
                    .filter(buy -> buy.ltp() != null && buy.ltp().compareTo(BigDecimal.ZERO) > 0)
                    .map(buy -> evaluateCandidate(optionType, sellWall, buy, lotSize, lotSizeBd, expiry))
                    .filter(dto -> dto != null
                            && dto.getMaxLoss().compareTo(BigDecimal.ZERO) > 0
                            && dto.getMaxLoss().compareTo(MAX_ALLOWED_LOSS) <= 0)
                    // Best R:R first; tie-break by highest Max Profit
                    .max(Comparator.comparing(SpreadSuggestionDto::getRiskReward)
                            .thenComparing(SpreadSuggestionDto::getMaxProfit))
                    .orElse(null);

            if (bestSpread == null) {
                log.info("ℹ️ [SPREAD] No valid {} spread found within ₹{} max loss for sell strike {}",
                        optionType, MAX_ALLOWED_LOSS, sellWall.strike());
                return null;
            }

            return objectMapper.writeValueAsString(bestSpread);

        } catch (Exception e) {
            log.warn("⚠️ [SPREAD] Failed to build spread suggestion (existing flow unaffected): {}", e.getMessage());
            return null;
        }
    }

    private SpreadSuggestionDto evaluateCandidate(
            String optionType,
            AdvisoryOiService.StrikeDetails sell,
            AdvisoryOiService.StrikeDetails buy,
            int lotSize,
            BigDecimal lotSizeBd,
            String expiry) {

        BigDecimal netCredit = sell.ltp().subtract(buy.ltp());
        if (netCredit.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal width = sell.strike().subtract(buy.strike()).abs();
        BigDecimal maxProfit = netCredit.multiply(lotSizeBd).setScale(2, RoundingMode.HALF_UP);
        BigDecimal maxLoss = width.subtract(netCredit).multiply(lotSizeBd).setScale(2, RoundingMode.HALF_UP);

        if (maxLoss.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal riskReward = maxProfit.divide(maxLoss, 4, RoundingMode.HALF_UP);
        boolean isPe = "PE".equalsIgnoreCase(optionType);
        BigDecimal breakeven = isPe
                ? sell.strike().subtract(netCredit)
                : sell.strike().add(netCredit);

        return SpreadSuggestionDto.builder()
                .spreadType(isPe ? "PE_SPREAD" : "CE_SPREAD")
                .direction(isPe ? "BULLISH" : "BEARISH")
                .expiry(expiry)
                .lotSize(lotSize)
                .sellLeg(SpreadSuggestionDto.Leg.builder()
                        .action("SELL")
                        .optionType(optionType)
                        .strike(sell.strike())
                        .ltp(sell.ltp())
                        .delta(sell.delta())
                        .iv(sell.iv())
                        .build())
                .buyLeg(SpreadSuggestionDto.Leg.builder()
                        .action("BUY")
                        .optionType(optionType)
                        .strike(buy.strike())
                        .ltp(buy.ltp())
                        .delta(buy.delta())
                        .iv(buy.iv())
                        .build())
                .netCredit(netCredit.setScale(2, RoundingMode.HALF_UP))
                .maxProfit(maxProfit)
                .maxLoss(maxLoss)
                .riskReward(riskReward)
                .breakeven(breakeven.setScale(2, RoundingMode.HALF_UP))
                .build();
    }
}