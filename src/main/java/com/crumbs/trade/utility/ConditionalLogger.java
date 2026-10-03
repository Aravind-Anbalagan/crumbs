package com.crumbs.trade.utility;

import org.slf4j.Logger;
import com.crumbs.trade.entity.Strategy;

/**
 * Smart logger wrapper that conditionally logs based on strategy flag.
 * Thread-safe implementation using ThreadLocal for concurrent environments.
 *
 * Behavior:
 * - ERROR logs are ALWAYS written regardless of flag
 * - DEBUG/INFO/WARN logs are written only when flag is enabled for the current thread
 *
 * Usage:
 * <pre>
 * private static final Logger baseLogger = LoggerFactory.getLogger(MyService.class);
 * private final ConditionalLogger logger = new ConditionalLogger(baseLogger);
 *
 * public void myMethod(String strategyName) {
 *     try {
 *         Strategy strategy = strategyRepo.findByName(strategyName);
 *         logger.setLoggingEnabled(strategy); // Set flag for current thread
 *
 *         logger.info("This logs only if strategy.enableLogging = 'Y'");
 *         logger.error("This ALWAYS logs");
 *     } finally {
 *         logger.clear(); // CRITICAL: Prevent memory leaks in thread pools
 *     }
 * }
 * </pre>
 *
 * @author Crumbs Trade
 * @version 2.0
 */
public class ConditionalLogger {

    private final Logger logger;

    // ThreadLocal ensures thread safety in singleton/static contexts
    private final ThreadLocal<Boolean> loggingEnabled = ThreadLocal.withInitial(() -> false);

    /**
     * Create a conditional logger wrapper
     *
     * @param logger The underlying SLF4J logger
     */
    public ConditionalLogger(Logger logger) {
        if (logger == null) {
            throw new IllegalArgumentException("Logger cannot be null");
        }
        this.logger = logger;
    }

    /**
     * Update logging state based on boolean flag for the current thread
     *
     * @param enabled true to enable INFO/DEBUG/WARN logs, false to disable
     */
    public void setLoggingEnabled(boolean enabled) {
        this.loggingEnabled.set(enabled);
    }

    /**
     * Update logging state from strategy entity for the current thread
     * Checks strategy.getEnableLogging() field (Y/N)
     *
     * @param strategy The strategy entity containing logging flag
     */
    public void setLoggingEnabled(Strategy strategy) {
        if (strategy != null && strategy.getEnableLogging() != null) {
            this.loggingEnabled.set("Y".equalsIgnoreCase(strategy.getEnableLogging()));
        } else {
            this.loggingEnabled.set(false);
        }
    }

    /**
     * Clears the ThreadLocal state.
     * MUST be called in a finally block to prevent memory leaks in thread-pooled environments.
     */
    public void clear() {
        this.loggingEnabled.remove();
    }

    // ================== DEBUG METHODS ==================

    public void debug(String msg) {
        if (loggingEnabled.get() && logger.isDebugEnabled()) {
            logger.debug(msg);
        }
    }

    public void debug(String format, Object arg) {
        if (loggingEnabled.get() && logger.isDebugEnabled()) {
            logger.debug(format, arg);
        }
    }

    public void debug(String format, Object... arguments) {
        if (loggingEnabled.get() && logger.isDebugEnabled()) {
            logger.debug(format, arguments);
        }
    }

    public void debug(String msg, Throwable t) {
        if (loggingEnabled.get() && logger.isDebugEnabled()) {
            logger.debug(msg, t);
        }
    }

    // ================== INFO METHODS ==================

    public void info(String msg) {
        if (loggingEnabled.get() && logger.isInfoEnabled()) {
            logger.info(msg);
        }
    }

    public void info(String format, Object arg) {
        if (loggingEnabled.get() && logger.isInfoEnabled()) {
            logger.info(format, arg);
        }
    }

    public void info(String format, Object... arguments) {
        if (loggingEnabled.get() && logger.isInfoEnabled()) {
            logger.info(format, arguments);
        }
    }

    public void info(String msg, Throwable t) {
        if (loggingEnabled.get() && logger.isInfoEnabled()) {
            logger.info(msg, t);
        }
    }

    // ================== WARN METHODS ==================

    public void warn(String msg) {
        if (loggingEnabled.get() && logger.isWarnEnabled()) {
            logger.warn(msg);
        }
    }

    public void warn(String format, Object arg) {
        if (loggingEnabled.get() && logger.isWarnEnabled()) {
            logger.warn(format, arg);
        }
    }

    public void warn(String format, Object... arguments) {
        if (loggingEnabled.get() && logger.isWarnEnabled()) {
            logger.warn(format, arguments);
        }
    }

    public void warn(String msg, Throwable t) {
        if (loggingEnabled.get() && logger.isWarnEnabled()) {
            logger.warn(msg, t);
        }
    }

    // ================== ERROR METHODS (ALWAYS LOG) ==================

    public void error(String msg) {
        logger.error(msg);
    }

    public void error(String format, Object arg) {
        logger.error(format, arg);
    }

    public void error(String format, Object... arguments) {
        logger.error(format, arguments);
    }

    public void error(String msg, Throwable t) {
        logger.error(msg, t);
    }

    // ================== UTILITY METHODS ==================

    public boolean isLoggingEnabled() {
        return loggingEnabled.get();
    }

    public boolean isDebugEnabled() {
        return loggingEnabled.get() && logger.isDebugEnabled();
    }

    public boolean isInfoEnabled() {
        return loggingEnabled.get() && logger.isInfoEnabled();
    }

    public boolean isWarnEnabled() {
        return loggingEnabled.get() && logger.isWarnEnabled();
    }

    public boolean isErrorEnabled() {
        return logger.isErrorEnabled();
    }

    public Logger getUnderlyingLogger() {
        return logger;
    }

    /**
     * Temporarily enable all logging for a code block on the current thread.
     *
     * Usage:
     * <pre>
     * try (AutoCloseable restorer = logger.temporarilyEnable()) {
     *     logger.info("This will log regardless of flag");
     * }
     * </pre>
     *
     * @return AutoCloseable that restores original thread state when closed
     */
    public AutoCloseable temporarilyEnable() {
        final boolean originalState = this.loggingEnabled.get();
        this.loggingEnabled.set(true);

        return () -> this.loggingEnabled.set(originalState);
    }

    @Override
    public String toString() {
        return String.format("ConditionalLogger[enabledForCurrentThread=%s, logger=%s]",
                loggingEnabled.get(), logger.getName());
    }
}