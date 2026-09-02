package com.crumbs.trade.builder;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionScannerConfig {

    /**
     * Number of calendar months forward to scan from today (e.g., 1, 2, 3).
     */
    @Builder.Default
    private int monthsToScan = 1;

    /**
     * Include weekly expiry contracts.
     */
    @Builder.Default
    private boolean scanWeekly = true;

    /**
     * Include monthly expiry contracts.
     */
    @Builder.Default
    private boolean scanMonthly = true;

    /**
     * Number of strikes above and below ATM to include (e.g., 10 strikes up & down).
     */
    @Builder.Default
    private int strikeDistance = 10;

    /**
     * Allowed moneyness types: ATM, ITM, OTM.
     */
    @Builder.Default
    private Set<Moneyness> allowedMoneyness = Set.of(Moneyness.ATM, Moneyness.ITM);

    public enum Moneyness {
        ATM, ITM, OTM
    }
}