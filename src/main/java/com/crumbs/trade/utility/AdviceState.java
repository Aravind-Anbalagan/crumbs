package com.crumbs.trade.utility;



public enum AdviceState {

    ACTIVE_CONFIRMED,
    ACTIVE_UNDER_PRESSURE,

    // 🔥 ADD THIS
    ACTIVE_AGING,

    EXITED,
    NO_SIGNAL
}
