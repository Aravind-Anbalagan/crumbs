package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.dto.CPR;
import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.entity.PricesIndex;
import org.json.JSONObject;

/**
 * Pure utility service — stateless helpers, no repo/broker dependencies.
 * All SmartConnect interactions receive the session object as a parameter.
 */
@Service
public class PriceUtilService {

    Logger logger = LoggerFactory.getLogger(PriceUtilService.class);

    // =========================================================
    // LTP / Price helpers
    // =========================================================

    public JSONObject getLTPWithRetry(SmartConnect smartConnect, String exchange, String symbol, String token) {
        int maxAttempts = 5;
        long backoff = 1000;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                JSONObject jsonObject = smartConnect.getLTP(exchange, symbol, token);
                if (jsonObject != null) return jsonObject;
                logger.warn("LTP returned null for {} (attempt {}/{}), retrying in {} ms", symbol, attempt, maxAttempts, backoff);
            } catch (Exception e) {
                logger.warn("LTP error for {} (attempt {}/{}): {}, retrying in {} ms", symbol, attempt, maxAttempts, e.getMessage(), backoff);
            }
            try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
            backoff *= 2;
        }
        logger.error("LTP failed for {} after {} attempts — skipping", symbol, maxAttempts);
        return null;
    }

    public BigDecimal getcurrentPrice(SmartConnect smartConnect, String exchange,
                                      String tradingSymbol, String symboltoken, String keyword) {
        int maxRetries = 3;
        long retryDelayMs = 1000;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                JSONObject jsonObject = smartConnect.getLTP(exchange, tradingSymbol, symboltoken);
                if (jsonObject != null && jsonObject.has(keyword)) {
                    return new BigDecimal(String.valueOf(jsonObject.get(keyword)));
                }
                throw new RuntimeException("Invalid LTP response");
            } catch (Exception e) {
                if (attempt == maxRetries) throw new RuntimeException("Failed to fetch LTP after " + maxRetries + " attempts", e);
                try { Thread.sleep(retryDelayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        throw new RuntimeException("Unexpected error in LTP retry");
    }

    public BigDecimal getPrice(SmartConnect smartConnect, Indicator stock, String type) {
        try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }
        return getcurrentPrice(smartConnect, stock.getExchange(), stock.getTradingSymbol(), stock.getToken(), type);
    }

    // =========================================================
    // Date helpers
    // =========================================================

    public String calculateDate(String type) {
        LocalDate today = LocalDate.now();
        LocalDate dateBefore52Weeks = today.minus(52, ChronoUnit.WEEKS);
        if ("TODAY".equalsIgnoreCase(type))              return today.toString().concat(" 09:15");
        if ("WEEK".equalsIgnoreCase(type) || "52WEEKS_EARLY".equalsIgnoreCase(type)) return dateBefore52Weeks.toString().concat(" 09:15");
        if ("HOUR".equalsIgnoreCase(type) || "TODAY_EOD".equalsIgnoreCase(type))     return today.toString().concat(" 15:15");
        if ("MONTHLY".equalsIgnoreCase(type))            return today.minus(2, ChronoUnit.YEARS).toString().concat(" 09:15");
        if ("MONTH_MINUS".equalsIgnoreCase(type))        return today.minus(1, ChronoUnit.MONTHS).toString().concat(" 09:15");
        if ("FIVE_DAYS_MINUS".equalsIgnoreCase(type))    return today.minus(5, ChronoUnit.DAYS).toString().concat(" 09:15");
        return null;
    }

    public LocalDateTime getCurrentDate() {
        return LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
    }

    public String getStartOfMonthExcludingWeekends() {
        LocalDate twoYearsAgo = LocalDate.now().minusYears(2);
        LocalDate firstDayOfMonth = LocalDate.of(twoYearsAgo.getYear(), twoYearsAgo.getMonth(), 1);
        while (firstDayOfMonth.getDayOfWeek() == DayOfWeek.SATURDAY || firstDayOfMonth.getDayOfWeek() == DayOfWeek.SUNDAY) {
            firstDayOfMonth = firstDayOfMonth.plusDays(1);
        }
        return firstDayOfMonth.atStartOfDay().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    public String getHourAndMinutes(String time, int interval, String type) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        ZonedDateTime adjusted = now.truncatedTo(ChronoUnit.MINUTES).minusMinutes(interval);
        int hour = adjusted.getHour();

        if (type.equalsIgnoreCase("MCX") && time.equalsIgnoreCase("FROM"))               return " 23:25";
        if ((type.equalsIgnoreCase("NFO") || type.equalsIgnoreCase("NSE")) && time.equalsIgnoreCase("FROM")) return " 09:20";

        String result;
        if (time.equalsIgnoreCase("FROM")) {
            result = " " + checkDigit(hour) + ":" + checkDigit(adjusted.getMinute());
            if (" 09:20".equalsIgnoreCase(result)) result = " 15:25";
        } else {
            result = " " + checkDigit(hour) + ":" + checkDigit(adjusted.getMinute());
        }
        return result;
    }

    public String checkTime(int hour, int minute) {
        String updateMin = (minute == 0) ? "00" : String.valueOf(minute);
        String updateHour = (hour >= -9 && hour <= 9) ? "0" + hour : String.valueOf(hour);
        logger.info("Monitor TimeFrame: {}:{}", updateHour, updateMin);
        return updateHour + ":" + updateMin;
    }

    public String checkDigit(int number) {
        return (number >= -9 && number <= 9) ? "0" + number : String.valueOf(number);
    }

    public String intToString(int value) {
        boolean isDoubleDigit = (value > 9 && value < 100) || (value < -9 && value > -100);
        return isDoubleDigit ? String.valueOf(value) : "0".concat(String.valueOf(value));
    }

    public int[] adjustedTime() {
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime localDateTime_TO = LocalDateTime.parse(date, dtf);
        int minutes = localDateTime_TO.getMinute();

        if (minutes < 15) {
            for (int i = minutes; i > 0; i--) localDateTime_TO = localDateTime_TO.minusMinutes(1);
            for (int j = 15; j > 0; j--) localDateTime_TO = localDateTime_TO.minusMinutes(1);
        } else if (minutes < 45 && minutes > 15) {
            for (int i = minutes; i > 15; i--) localDateTime_TO = localDateTime_TO.minusMinutes(1);
        } else if (minutes >= 45) {
            for (int i = minutes; i > 45; i--) localDateTime_TO = localDateTime_TO.minusMinutes(1);
        }

        LocalDateTime localDateTime_FROM = localDateTime_TO.minusMinutes(30);
        return new int[]{ localDateTime_FROM.getHour(), localDateTime_FROM.getMinute(),
                          localDateTime_TO.getHour(),   localDateTime_TO.getMinute() };
    }

    // =========================================================
    // Price type / candle helpers
    // =========================================================

    public String getPriceType(BigDecimal open, BigDecimal close) {
        return (close.compareTo(open) >= 1) ? "BUY" : "SELL";
    }

    public String findOpenAndClose(PricesIndex pricesEq) {
        BigDecimal difference = pricesEq.getOpen().subtract(pricesEq.getClose()).abs();
        return (difference.compareTo(new BigDecimal("1.00")) <= 0) ? "TRUE" : "FALSE";
    }

    public BigDecimal convertStringToList(String input, String type) {
        List<Integer> numberList = Arrays.stream(input.replaceAll("\\[|\\]", "").split(","))
                .map(String::trim).map(Integer::parseInt).sorted().collect(Collectors.toList());
        return "BUY".equalsIgnoreCase(type) ? new BigDecimal(numberList.get(0)) : new BigDecimal(numberList.get(2));
    }

    // =========================================================
    // CPR calculation
    // =========================================================

    public CPR calculateCpr(BigDecimal high, BigDecimal low, BigDecimal close) {
        if (high.compareTo(low) < 0) { BigDecimal tmp = high; high = low; low = tmp; }

        BigDecimal pivot   = high.add(low).add(close).divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        BigDecimal bc_raw  = high.add(low).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        BigDecimal tc_raw  = pivot.multiply(BigDecimal.valueOf(2)).subtract(bc_raw);

        String type, desc;
        if (tc_raw.compareTo(bc_raw) > 0)      { type = "ASCENDING CPR";  desc = "Bullish CPR structure (TC > BC) — Positive bias."; }
        else if (bc_raw.compareTo(tc_raw) > 0) { type = "DESCENDING CPR"; desc = "Bearish CPR structure (BC > TC) — Negative bias."; }
        else                                   { type = "INSIDE CPR";     desc = "Balanced CPR structure — Neutral bias."; }

        BigDecimal topDisplay    = bc_raw.max(tc_raw);
        BigDecimal bottomDisplay = bc_raw.min(tc_raw);
        BigDecimal width         = topDisplay.subtract(bottomDisplay).abs();
        BigDecimal widthPercent  = width.divide(pivot, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).abs().setScale(2, RoundingMode.HALF_UP);

        String widthType;
        if      (widthPercent.compareTo(BigDecimal.valueOf(0.25)) < 0) { widthType = "NARROW CPR"; desc += " Narrow CPR — Trending move likely."; }
        else if (widthPercent.compareTo(BigDecimal.valueOf(0.50)) < 0) { widthType = "MEDIUM CPR"; desc += " Medium CPR — Moderate volatility expected."; }
        else                                                           { widthType = "WIDE CPR";   desc += " Wide CPR — Possible sideways/choppy market."; }

        CPR cpr = new CPR();
        cpr.setPivot(pivot);
        cpr.setTop_pivot(topDisplay);
        cpr.setBottom_pivot(bottomDisplay);
        cpr.setCprType(type);
        cpr.setWidthType(widthType);
        cpr.setDescription(desc);
        return cpr;
    }

    // =========================================================
    // Error / retry helpers
    // =========================================================

    public boolean isRateLimitError(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("rate limit");
    }

    public <T> T retryWithBackoff(Callable<T> task, int maxRetries, long initialBackoffMs) throws Exception {
        long backoff = initialBackoffMs;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return task.call();
            } catch (Exception e) {
                if (isRateLimitError(e) && attempt < maxRetries) {
                    logger.warn("Rate limit hit, retrying in {} ms (attempt {}/{})", backoff, attempt, maxRetries);
                    Thread.sleep(backoff);
                    backoff *= 2;
                } else { throw e; }
            }
        }
        throw new RuntimeException("Max retries exceeded");
    }
}