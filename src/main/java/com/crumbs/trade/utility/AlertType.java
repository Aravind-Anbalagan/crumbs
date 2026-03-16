package com.crumbs.trade.utility;

public enum AlertType {

    CE_PE_CROSSOVER,        // existing logic — CE crosses above PE
    PE_CE_CROSSOVER,        // existing logic — PE crosses above CE
    VWAP_DOMINANCE_CE,      // NEW — CE > CE-VWAP AND PE < PE-VWAP
    VWAP_DOMINANCE_PE       // NEW — PE > PE-VWAP AND CE < CE-VWAP
}