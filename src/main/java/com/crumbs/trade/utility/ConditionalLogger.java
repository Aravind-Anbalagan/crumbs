package com.crumbs.trade.utility;


import org.slf4j.Logger;
import com.crumbs.trade.entity.Strategy;

/**
 * Smart logger wrapper that conditionally logs based on strategy flag.
 * 
 * Behavior:
 * - ERROR logs are ALWAYS written regardless of flag
 * - DEBUG/INFO/WARN logs are written only when flag is enabled
 * 
 * Usage:
 * <pre>
 * private static final Logger baseLogger = LoggerFactory.getLogger(MyService.class);
 * private final ConditionalLogger logger = new ConditionalLogger(baseLogger);
 * 
 * public void myMethod(String strategyName) {
 *     Strategy strategy = strategyRepo.findByName(strategyName);
 *     logger.setLoggingEnabled(strategy); // Set flag from strategy
 *     
 *     logger.info("This logs only if strategy.enableLogging = 'Y'");
 *     logger.error("This ALWAYS logs");
 * }
 * </pre>
 * 
 * @author Crumbs Trade
 * @version 1.0
 */
public class ConditionalLogger {
    
    private final Logger logger;
    private volatile boolean loggingEnabled;
    
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
        this.loggingEnabled = false; // Default disabled
    }
    
    /**
     * Update logging state based on boolean flag
     * 
     * @param enabled true to enable INFO/DEBUG/WARN logs, false to disable
     */
    public void setLoggingEnabled(boolean enabled) {
        this.loggingEnabled = enabled;
    }
    
    /**
     * Update logging state from strategy entity
     * Checks strategy.getEnableLogging() field (Y/N)
     * 
     * @param strategy The strategy entity containing logging flag
     */
    public void setLoggingEnabled(Strategy strategy) {
        if (strategy != null && strategy.getEnableLogging() != null) {
            this.loggingEnabled = "Y".equalsIgnoreCase(strategy.getEnableLogging());
        } else {
            this.loggingEnabled = false;
        }
    }
    
    // ================== DEBUG METHODS ==================
    
    /**
     * Log a message at DEBUG level (only if enabled)
     */
    public void debug(String msg) {
        if (loggingEnabled && logger.isDebugEnabled()) {
            logger.debug(msg);
        }
    }
    
    /**
     * Log a message at DEBUG level with one parameter (only if enabled)
     */
    public void debug(String format, Object arg) {
        if (loggingEnabled && logger.isDebugEnabled()) {
            logger.debug(format, arg);
        }
    }
    
    /**
     * Log a message at DEBUG level with multiple parameters (only if enabled)
     */
    public void debug(String format, Object... arguments) {
        if (loggingEnabled && logger.isDebugEnabled()) {
            logger.debug(format, arguments);
        }
    }
    
    /**
     * Log a message at DEBUG level with exception (only if enabled)
     */
    public void debug(String msg, Throwable t) {
        if (loggingEnabled && logger.isDebugEnabled()) {
            logger.debug(msg, t);
        }
    }
    
    // ================== INFO METHODS ==================
    
    /**
     * Log a message at INFO level (only if enabled)
     */
    public void info(String msg) {
        if (loggingEnabled && logger.isInfoEnabled()) {
            logger.info(msg);
        }
    }
    
    /**
     * Log a message at INFO level with one parameter (only if enabled)
     */
    public void info(String format, Object arg) {
        if (loggingEnabled && logger.isInfoEnabled()) {
            logger.info(format, arg);
        }
    }
    
    /**
     * Log a message at INFO level with multiple parameters (only if enabled)
     */
    public void info(String format, Object... arguments) {
        if (loggingEnabled && logger.isInfoEnabled()) {
            logger.info(format, arguments);
        }
    }
    
    /**
     * Log a message at INFO level with exception (only if enabled)
     */
    public void info(String msg, Throwable t) {
        if (loggingEnabled && logger.isInfoEnabled()) {
            logger.info(msg, t);
        }
    }
    
    // ================== WARN METHODS ==================
    
    /**
     * Log a message at WARN level (only if enabled)
     */
    public void warn(String msg) {
        if (loggingEnabled && logger.isWarnEnabled()) {
            logger.warn(msg);
        }
    }
    
    /**
     * Log a message at WARN level with one parameter (only if enabled)
     */
    public void warn(String format, Object arg) {
        if (loggingEnabled && logger.isWarnEnabled()) {
            logger.warn(format, arg);
        }
    }
    
    /**
     * Log a message at WARN level with multiple parameters (only if enabled)
     */
    public void warn(String format, Object... arguments) {
        if (loggingEnabled && logger.isWarnEnabled()) {
            logger.warn(format, arguments);
        }
    }
    
    /**
     * Log a message at WARN level with exception (only if enabled)
     */
    public void warn(String msg, Throwable t) {
        if (loggingEnabled && logger.isWarnEnabled()) {
            logger.warn(msg, t);
        }
    }
    
    // ================== ERROR METHODS (ALWAYS LOG) ==================
    
    /**
     * Log a message at ERROR level (ALWAYS logged, ignores flag)
     */
    public void error(String msg) {
        logger.error(msg); // ALWAYS log errors
    }
    
    /**
     * Log a message at ERROR level with one parameter (ALWAYS logged)
     */
    public void error(String format, Object arg) {
        logger.error(format, arg); // ALWAYS log errors
    }
    
    /**
     * Log a message at ERROR level with multiple parameters (ALWAYS logged)
     */
    public void error(String format, Object... arguments) {
        logger.error(format, arguments); // ALWAYS log errors
    }
    
    /**
     * Log a message at ERROR level with exception (ALWAYS logged)
     */
    public void error(String msg, Throwable t) {
        logger.error(msg, t); // ALWAYS log errors
    }
    
    // ================== UTILITY METHODS ==================
    
    /**
     * Check if conditional logging is currently enabled
     * 
     * @return true if INFO/DEBUG/WARN logs will be written
     */
    public boolean isLoggingEnabled() {
        return loggingEnabled;
    }
    
    /**
     * Check if DEBUG level is enabled (respects both flag and logger config)
     * 
     * @return true if debug logs will be written
     */
    public boolean isDebugEnabled() {
        return loggingEnabled && logger.isDebugEnabled();
    }
    
    /**
     * Check if INFO level is enabled (respects both flag and logger config)
     * 
     * @return true if info logs will be written
     */
    public boolean isInfoEnabled() {
        return loggingEnabled && logger.isInfoEnabled();
    }
    
    /**
     * Check if WARN level is enabled (respects both flag and logger config)
     * 
     * @return true if warn logs will be written
     */
    public boolean isWarnEnabled() {
        return loggingEnabled && logger.isWarnEnabled();
    }
    
    /**
     * Check if ERROR level is enabled (always true in typical configs)
     * 
     * @return true if error logs will be written
     */
    public boolean isErrorEnabled() {
        return logger.isErrorEnabled(); // ERROR always checked
    }
    
    /**
     * Get the underlying SLF4J logger (for advanced use cases)
     * Use this if you need to bypass conditional logic temporarily
     * 
     * @return The wrapped logger instance
     */
    public Logger getUnderlyingLogger() {
        return logger;
    }
    
    /**
     * Temporarily enable all logging for a code block
     * Useful for critical debugging sections
     * 
     * Usage:
     * <pre>
     * try (AutoCloseable restorer = logger.temporarilyEnable()) {
     *     logger.info("This will log regardless of flag");
     * }
     * </pre>
     * 
     * @return AutoCloseable that restores original state when closed
     */
    public AutoCloseable temporarilyEnable() {
        final boolean originalState = this.loggingEnabled;
        this.loggingEnabled = true;
        
        return () -> this.loggingEnabled = originalState;
    }
    
    /**
     * Get string representation for debugging
     */
    @Override
    public String toString() {
        return String.format("ConditionalLogger[enabled=%s, logger=%s]", 
            loggingEnabled, logger.getName());
    }
}
