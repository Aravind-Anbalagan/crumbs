package com.crumbs.trade.utility;

import org.slf4j.Logger;

public class TimerLog {

    public static long start() {
        return System.currentTimeMillis();
    }

    public static void end(Logger logger, String step, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("⏱ [TIMER] {} took {} ms", step, elapsed);
    }
}