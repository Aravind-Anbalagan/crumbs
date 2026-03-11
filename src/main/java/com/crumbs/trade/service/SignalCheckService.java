package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.entity.PSARIndex;
import com.crumbs.trade.entity.PSARMcx;
import com.crumbs.trade.entity.PSARNifty;
import com.crumbs.trade.entity.PricesHeikinAshiIndex;
import com.crumbs.trade.entity.PricesHeikinAshiMcx;
import com.crumbs.trade.entity.PricesHeikinAshiNifty;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.PricesMcx;
import com.crumbs.trade.entity.ResultMcx;
import com.crumbs.trade.entity.ResultNifty;
import com.crumbs.trade.repo.PriceHeikinashiIndexRepo;
import com.crumbs.trade.repo.PriceHeikinashiMcxRepo;
import com.crumbs.trade.repo.PriceHeikinashiNiftyRepo;
import com.crumbs.trade.repo.PricesIndexRepo;
import com.crumbs.trade.repo.PricesMcxRepo;
import com.crumbs.trade.repo.PsarIndexRepo;
import com.crumbs.trade.repo.PsarMcxRepo;
import com.crumbs.trade.repo.PsarNiftyRepo;
import com.crumbs.trade.repo.ResultMcxRepo;
import com.crumbs.trade.repo.ResultNiftyRepo;

import jakarta.mail.MessagingException;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Handles support/resistance signal detection and intraday monitoring.
 */
@Service
public class SignalCheckService {

    Logger logger = LoggerFactory.getLogger(SignalCheckService.class);

    @Autowired PsarIndexRepo psarIndexRepo;
    @Autowired PriceHeikinashiIndexRepo priceHeikinashiIndexRepo;
    @Autowired PricesIndexRepo pricesIndexRepo;
    @Autowired PricesMcxRepo pricesMcxRepo;
    @Autowired PriceHeikinashiNiftyRepo priceHeikinashiNiftyRepo;
    @Autowired PsarNiftyRepo psarNiftyRepo;
    @Autowired ResultNiftyRepo resultNiftyRepo;
    @Autowired PriceHeikinashiMcxRepo priceHeikinashiMcxRepo;
    @Autowired PsarMcxRepo psarMcxRepo;
    @Autowired ResultMcxRepo resultMcxRepo;
    @Autowired ResultService resultService;

    // =========================================================
    // PSAR / Heikin-Ashi entry checks
    // =========================================================

    public String checkEntryPsar(String type, String stockName, String timeframe) {
        if (type.equalsIgnoreCase("INDEX")) {
            List<PSARIndex> list = psarIndexRepo.findByNameAndTimeframeOrderByIdDesc(stockName, timeframe, PageRequest.of(0, 2));
            if (list != null && list.size() >= 2) {
                return list.get(0).getType().equalsIgnoreCase(list.get(1).getType())
                        ? list.get(0).getType()
                        : "FIRST ".concat(list.get(0).getType());
            }
        }
        return null;
    }

    public String checkEntryHeikinAshi(String type, String stockName, String timeframe) {
        if (type.equalsIgnoreCase("INDEX")) {
            List<PricesHeikinAshiIndex> list = priceHeikinashiIndexRepo.findByNameAndTimeframeOrderByIdDesc(stockName, timeframe, PageRequest.of(0, 2));
            if (list != null && list.size() >= 2) {
                return list.get(0).getType().equalsIgnoreCase(list.get(1).getType())
                        ? list.get(0).getType()
                        : "FIRST ".concat(list.get(0).getType());
            }
        }
        return null;
    }

    // =========================================================
    // Support / Resistance signal checks
    // =========================================================

    public Indicator checkForHourlySignal(Indicator indicator, List<String> supportList,
            List<String> resistanceList, BigDecimal index_CurrentPrice, BigDecimal avgRange) {
        boolean supportFlag = false, resistanceFlag = false;
        for (String support : supportList) {
            if (inRange(index_CurrentPrice, avgRange, new BigDecimal(support.split("=")[1]))) supportFlag = true;
        }
        for (String resistance : resistanceList) {
            if (inRange(index_CurrentPrice, avgRange, new BigDecimal(resistance.split("=")[1]))) resistanceFlag = true;
        }
        if (supportFlag && resistanceFlag) indicator.setHourlySignal("SUPPORT + RESISTANCE");
        else if (resistanceFlag)           indicator.setHourlySignal("RESISTANCE");
        else if (supportFlag)              indicator.setHourlySignal("SUPPORT");
        return indicator;
    }

    public Indicator checkForDaySignal(Indicator indicator, List<String> supportList,
            List<String> resistanceList, BigDecimal index_CurrentPrice, BigDecimal avgRange) {
        boolean supportFlag = false, resistanceFlag = false;
        for (String support : supportList) {
            if (inRange(index_CurrentPrice, avgRange, new BigDecimal(support.split("=")[1]))) supportFlag = true;
        }
        for (String resistance : resistanceList) {
            if (inRange(index_CurrentPrice, avgRange, new BigDecimal(resistance.split("=")[1]))) resistanceFlag = true;
        }
        if (supportFlag && resistanceFlag) indicator.setDailysignal("SUPPORT + RESISTANCE");
        else if (resistanceFlag)           indicator.setDailysignal("RESISTANCE");
        else if (supportFlag)              indicator.setDailysignal("SUPPORT");

        // 52-week proximity flags
        if (indicator.getFifty2_weeklow() != null && inRange(index_CurrentPrice, avgRange, indicator.getFifty2_weeklow()))
            indicator.setFifty2week_flag("NEAT 52 WEEK LOW");
        else if (indicator.getFifty2_weekhigh() != null && inRange(index_CurrentPrice, avgRange, indicator.getFifty2_weekhigh()))
            indicator.setFifty2week_flag("NEAT 52 WEEK HIGH");

        return indicator;
    }

    public Indicator checkForFourHourSignal(Indicator indicator, List<String> supportList,
            List<String> resistanceList, BigDecimal index_CurrentPrice, BigDecimal avgRange) {
        boolean supportFlag = false, resistanceFlag = false;
        for (String support : supportList) {
            if (inRange(index_CurrentPrice, avgRange, new BigDecimal(support.split("=")[1]))) supportFlag = true;
        }
        for (String resistance : resistanceList) {
            if (inRange(index_CurrentPrice, avgRange, new BigDecimal(resistance.split("=")[1]))) resistanceFlag = true;
        }
        if (supportFlag && resistanceFlag) indicator.setFourHoursignal("SUPPORT + RESISTANCE");
        else if (resistanceFlag)           indicator.setFourHoursignal("RESISTANCE");
        else if (supportFlag)              indicator.setFourHoursignal("SUPPORT");
        return indicator;
    }

    public Indicator checkForMonthlySignal(Indicator indicator, List<String> supportList,
            List<String> resistanceList, BigDecimal index_CurrentPrice, BigDecimal avgRange) {
        boolean supportFlag = false, resistanceFlag = false;
        for (String support : supportList) {
            if (inRange(index_CurrentPrice, avgRange, new BigDecimal(support.split("=")[1]))) supportFlag = true;
        }
        for (String resistance : resistanceList) {
            if (inRange(index_CurrentPrice, avgRange, new BigDecimal(resistance.split("=")[1]))) resistanceFlag = true;
        }
        if (supportFlag && resistanceFlag) indicator.setMonthlysignal("SUPPORT + RESISTANCE");
        else if (resistanceFlag)           indicator.setMonthlysignal("RESISTANCE");
        else if (supportFlag)              indicator.setMonthlysignal("SUPPORT");
        return indicator;
    }

    public Indicator checkForWeekSignal(Indicator indicator, List<String> supportList,
            List<String> resistanceList, BigDecimal index_CurrentPrice, BigDecimal avgRange) {
        boolean supportFlag = false, resistanceFlag = false;
        for (String support : supportList) {
            if (inRange(index_CurrentPrice, avgRange, new BigDecimal(support.split("=")[1]))) supportFlag = true;
        }
        for (String resistance : resistanceList) {
            if (inRange(index_CurrentPrice, avgRange, new BigDecimal(resistance.split("=")[1]))) resistanceFlag = true;
        }
        if (supportFlag && resistanceFlag) indicator.setWeeklysignal("SUPPORT + RESISTANCE");
        else if (resistanceFlag)           indicator.setWeeklysignal("RESISTANCE");
        else if (supportFlag)              indicator.setWeeklysignal("SUPPORT");
        return indicator;
    }

    // =========================================================
    // NFO / MCX signal detection
    // =========================================================

    public void getSignal(BigDecimal index_CurrentPrice) {
        try {
            List<PricesIndex> priceList = pricesIndexRepo.findAllByOrderByIdDesc();
            PricesIndex prices = priceList.get(0);
            if (prices.getPercentage().compareTo(BigDecimal.ZERO) < 0) {
                List<Integer> HIGH_List = priceList.stream().limit(3).map(p -> p.getHigh().intValue()).collect(Collectors.toList());
                List<Integer> LOW_List  = priceList.stream().limit(3).map(p -> p.getLow().intValue()).collect(Collectors.toList());
                List<String> dateList   = priceList.stream().limit(3).map(p -> {
                    ZonedDateTime zdt = ZonedDateTime.parse(p.getTimestamp(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
                    return checkTime(zdt.getHour(), zdt.getMinute());
                }).collect(Collectors.toList());
                Collections.sort(dateList, Collections.reverseOrder());
                Collections.sort(HIGH_List, Collections.reverseOrder());
                Collections.sort(LOW_List);
                int max = HIGH_List.get(0), min = LOW_List.get(0);

                if (index_CurrentPrice.intValue() > max && prices.getResult() == null) prices.setSignal("CE");
                else if (index_CurrentPrice.intValue() < min)                          prices.setSignal("PE");

                pricesIndexRepo.save(prices);
            }
        } catch (Exception ex) { /* intentionally swallowed */ }
    }

    public void getSignalMcx(BigDecimal index_CurrentPrice) {
        try {
            List<PricesMcx> priceList = pricesMcxRepo.findAllByOrderByIdDesc();
            PricesMcx prices = priceList.get(0);
            if (prices.getPercentage().compareTo(BigDecimal.ZERO) < 0) {
                List<Integer> HIGH_List = priceList.stream().limit(3).map(p -> p.getHigh().intValue()).collect(Collectors.toList());
                List<Integer> LOW_List  = priceList.stream().limit(3).map(p -> p.getLow().intValue()).collect(Collectors.toList());
                Collections.sort(HIGH_List, Collections.reverseOrder());
                Collections.sort(LOW_List);
                int max = HIGH_List.get(0), min = LOW_List.get(0);
                String result = null;
                if (index_CurrentPrice.intValue() > max)      result = "BUY";
                else if (index_CurrentPrice.intValue() < min) result = "SELL";
                prices.setSignal(result);
                pricesMcxRepo.save(prices);
            }
        } catch (Exception ex) { /* intentionally swallowed */ }
    }

    public String getSignal_eq(String type, String name, String timeframe) {
        try {
            List<PricesIndex> priceList = pricesIndexRepo.findByNameAndTimeframeOrderByIdDesc(
                    name, timeframe, PageRequest.of(0, 3));
            if (priceList != null && priceList.size() >= 3) {
                List<Integer> HIGH_List = priceList.stream().limit(3).map(p -> p.getHigh().intValue()).sorted(Collections.reverseOrder()).collect(Collectors.toList());
                List<Integer> LOW_List  = priceList.stream().limit(3).map(p -> p.getLow().intValue()).sorted().collect(Collectors.toList());
                if ("high".equalsIgnoreCase(type)) return HIGH_List.toString();
                if ("low".equalsIgnoreCase(type))  return LOW_List.toString();
            }
        } catch (Exception ex) {
            logger.error("Error in getSignal_eq for {} [{}]: {}", name, timeframe, ex.getMessage());
        }
        return null;
    }

    // =========================================================
    // PSAR + Heikin-Ashi intraday monitor
    // =========================================================

    public void monitorPsarAndheikinachiStrategy(String exchange, BigDecimal currentPrice)
            throws MessagingException, IOException {
        LocalTime currentTime = LocalTime.now();
        LocalTime comparisonTime = LocalTime.of(15, 20);

        if ("NFO".equalsIgnoreCase(exchange)) {
            List<PricesHeikinAshiNifty> heikinAshiList = priceHeikinashiNiftyRepo.findAllByOrderByIdDesc();
            List<PSARNifty> psarList = psarNiftyRepo.findAllByOrderByIdDesc();
            if (!heikinAshiList.isEmpty() && !psarList.isEmpty()) {
                PricesHeikinAshiNifty ha = heikinAshiList.get(0);
                PSARNifty psar = psarList.get(0);
                String heikinachiFlag = ha.getType();
                String psarFlag = psar.getType();
                boolean lastTwoCandleFlag = false;

                ResultNifty resultNifty = resultNiftyRepo.findByActiveAndName("Y", "NIFTY");
                if (resultNifty != null) {
                    lastTwoCandleFlag = true;
                } else if (heikinAshiList.size() >= 3 &&
                           heikinAshiList.get(0).getType().equalsIgnoreCase(heikinAshiList.get(1).getType()) &&
                           heikinAshiList.get(1).getType().equalsIgnoreCase(heikinAshiList.get(2).getType())) {
                    lastTwoCandleFlag = true;
                }

                if (lastTwoCandleFlag) {
                    if ("BUY".equalsIgnoreCase(heikinachiFlag) && "BUY".equalsIgnoreCase(psarFlag))
                        resultService.savePsarHeikinAchiStrategyNifty(ha, psar, currentPrice);
                    else if ("SELL".equalsIgnoreCase(heikinachiFlag) && "SELL".equalsIgnoreCase(psarFlag))
                        resultService.savePsarHeikinAchiStrategyNifty(ha, psar, currentPrice);
                }
                if (currentTime.isAfter(comparisonTime))
                    resultService.savePsarHeikinAchiStrategyNifty(ha, psar, currentPrice);
            }

        } else if ("MCX".equalsIgnoreCase(exchange)) {
            List<PricesHeikinAshiMcx> heikinAshiList = priceHeikinashiMcxRepo.findAllByOrderByIdDesc();
            List<PSARMcx> psarList = psarMcxRepo.findAllByOrderByIdDesc();
            if (!heikinAshiList.isEmpty() && !psarList.isEmpty()) {
                PricesHeikinAshiMcx ha = heikinAshiList.get(0);
                PSARMcx psar = psarList.get(0);
                boolean lastTwoCandleFlag = false;

                ResultMcx resultMcx = resultMcxRepo.findByActiveAndName("Y", "MCX");
                if (resultMcx != null) {
                    lastTwoCandleFlag = true;
                } else if (heikinAshiList.size() >= 3 &&
                           heikinAshiList.get(0).getType().equalsIgnoreCase(heikinAshiList.get(1).getType()) &&
                           heikinAshiList.get(1).getType().equalsIgnoreCase(heikinAshiList.get(2).getType())) {
                    lastTwoCandleFlag = true;
                }

                if (lastTwoCandleFlag) {
                    if ("BUY".equalsIgnoreCase(ha.getType()) && "BUY".equalsIgnoreCase(psar.getType()))
                        resultService.savePsarHeikinAchiStrategyMcx(ha, psar, currentPrice);
                    else if ("SELL".equalsIgnoreCase(ha.getType()) && "SELL".equalsIgnoreCase(psar.getType()))
                        resultService.savePsarHeikinAchiStrategyMcx(ha, psar, currentPrice);
                }
            }
        }
    }

    // =========================================================
    // Private helpers
    // =========================================================

    private boolean inRange(BigDecimal currentPrice, BigDecimal avgRange, BigDecimal target) {
        return Range.between(currentPrice.subtract(avgRange), currentPrice.add(avgRange)).contains(target);
    }

    private String checkTime(int hour, int minute) {
        String updateMin  = (minute == 0) ? "00" : String.valueOf(minute);
        String updateHour = (hour >= -9 && hour <= 9) ? "0" + hour : String.valueOf(hour);
        return updateHour + ":" + updateMin;
    }
}