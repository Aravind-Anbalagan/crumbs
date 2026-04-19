package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.cache.CandleCache;
import com.crumbs.trade.dto.Candlestick;
import com.crumbs.trade.dto.OHLC;
import com.crumbs.trade.dto.StrategyDTO;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.ResultVix;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.entity.Vix;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.PricesIndexRepo;
import com.crumbs.trade.repo.ResultVixRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.repo.VixRepo;
import com.crumbs.trade.utility.NSEWorkingDays;
import com.crumbs.trade.utility.TimerLog;

import jakarta.mail.internet.AddressException;
import jakarta.transaction.Transactional;

@Service
public class ChartService {

    private static final Logger logger = LoggerFactory.getLogger(ChartService.class);

    // ================= CONFIGURATION CONSTANTS =================
    private static final String NIFTY = "NIFTY";
    private static final String CRUDEOILM = "CRUDEOILM";
    private static final String SILVERM = "SILVERM";
    
    private static final String EXCH_NSE = "NSE";
    private static final String EXCH_NFO = "NFO";
    private static final String EXCH_MCX = "MCX";

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    
    private static final BigDecimal NIFTY_TARGET = new BigDecimal("20.00");
    private static final BigDecimal NIFTY_SL     = new BigDecimal("10.00");
    private static final BigDecimal MCX_TARGET    = new BigDecimal("50.00");
    private static final BigDecimal MCX_SL        = new BigDecimal("25.00");

    private static final String DATE_FORMAT_FULL = "dd-MM-yyyy HH:mm:ss";
    private static final String DATE_FORMAT_SHORT = "yyyy-MM-dd HH:mm";

    @Autowired private AngelOne angelOne;
    @Autowired private PriceUtilService priceUtilService;
    @Autowired private StrategyHelperService strategyHelperService;
    @Autowired private HeikinAshiIndicator heikinAshiIndicator;
    @Autowired private VixRepo vixRepo;
    @Autowired private PSARIndicator pSARIndicator;
    @Autowired private ResultVixRepo resultVixRepo;
    @Autowired private IndexesRepo indexesRepo;
    @Autowired private AngelOneService angelOneService;
    @Autowired private PricesIndexRepo pricesIndexRepo;
    @Autowired private MovingAvgWithSMASmoothing movingAvgWithSMASmoothing;
    @Autowired private TelegramService telegramService;
    @Autowired private SuperTrendIndicator superTrendIndicator;
    @Autowired private VWAPIndicator vwapIndicator;
    @Autowired private StrategyRepo strategyRepo;
    @Autowired private CandleCache candleCache;

    // ================= CORE DATA FETCHING =================

    public JSONArray getJsonDetails(Indexes indexes, String fromDate, String toDate, String timeFrame) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                SmartConnect smartConnect = angelOne.signIn();
                JSONObject requestObject = new JSONObject();
                requestObject.put("exchange", indexes.getExchange());
                requestObject.put("symboltoken", indexes.getToken());
                requestObject.put("interval", timeFrame);
                requestObject.put("fromdate", fromDate);
                requestObject.put("todate", toDate);
                return smartConnect.candleData(requestObject);
            } catch (Exception ex) {
                if (attempt == maxRetries) break;
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        return new JSONArray();
    }

    public OHLC getOHLC(JSONArray ohlcArray) {
        OHLC ohlc = new OHLC();
        ohlc.setTimestamp(String.valueOf(ohlcArray.getString(0)));
        ohlc.setOpen(new BigDecimal(String.valueOf(ohlcArray.getDouble(1))));
        ohlc.setHigh(new BigDecimal(String.valueOf(ohlcArray.getDouble(2))));
        ohlc.setLow(new BigDecimal(String.valueOf(ohlcArray.getDouble(3))));
        ohlc.setClose(new BigDecimal(String.valueOf(ohlcArray.getDouble(4))));
        ohlc.setVolume(new BigDecimal(String.valueOf(ohlcArray.getDouble(5))));
        ohlc.setRange(ohlc.getHigh().subtract(ohlc.getLow()));
        return ohlc;
    }

    // ================= MAIN PIPELINE =================

    public String readChartData(String timeFrame, String type, boolean testflag, String name,
                                String fromDate, String toDate, String symbol) throws SmartAPIException {
        try {
            Indexes indexes = indexesRepo.findByNameAndSymbol(name, symbol);
            Strategy strategy = getTokenDetails(name, type);
            if (strategy != null && strategy.getName() != null) {
                readCandle(indexes, type, testflag, timeFrame, name, fromDate, toDate, "HEIKIN_PSAR");
                List<Candlestick> base = getValuesAsList(name);
                
                updateCandleData(heikinAshiIndicator.calculateHeikinAshiCandles(base), "HEIKINACHI");
                updateCandleData(pSARIndicator.calculatePSAR(base), "PSAR");
                updateCandleData(movingAvgWithSMASmoothing.getMovingAverage(base), "MA");
                updateCandleData(superTrendIndicator.calculateSuperTrend(base), "SUPER_TREND");
                updateCandleData(vwapIndicator.calculateVWAP(base), "VWAP");
            }
        } catch (Exception e) { logger.error("Pipeline Error: {}", e.getMessage()); }
        return "Completed";
    }

    // ================= SIGNAL MONITORING =================

    public void monitorSignal(String name, String type, boolean testFlag, int i) 
            throws AddressException, MessagingException, IOException {
        Strategy strategy = getTokenDetails(name, type);
        SmartConnect sc = angelOne.signIn();
        BigDecimal cp = angelOneService.getcurrentPrice(sc, strategy.getExchange(), strategy.getSymbol(), strategy.getToken());
        List<Vix> vixList = vixRepo.findAllByNameContainingOrderByIdDesc(name);
        ResultVix activeTrade = resultVixRepo.findByActiveTrueAndName(name);

        if (vixList != null && !vixList.isEmpty()) {
            Vix vix = vixList.get(i);
            if (testFlag) cp = vix.getClose();

            if (activeTrade == null) {
                if (vix.getType().equalsIgnoreCase(BUY) && buyEntrySignal(vix)) {
                    if (compareHeikinAchiAndPsarCandle(vixList, i)) makeEntry(vix, strategy, BUY, testFlag, cp);
                } else if (vix.getType().equalsIgnoreCase(SELL) && sellEntrySignal(vix)) {
                    if (compareHeikinAchiAndPsarCandle(vixList, i)) makeEntry(vix, strategy, SELL, testFlag, cp);
                }
            } else {
                if (vix.getType().equalsIgnoreCase(BUY) && buyExitSignal(vix)) makeEntry(vix, strategy, BUY, testFlag, cp);
                else if (vix.getType().equalsIgnoreCase(SELL) && sellExitSignal(vix)) makeEntry(vix, strategy, SELL, testFlag, cp);
            }
        }
    }

    // ================= TRADE EXECUTION =================

    @Transactional
    public void makeEntry(Vix vix, Strategy strategy, String type, boolean testFlag, BigDecimal cp) 
            throws AddressException, MessagingException, IOException {
        String now = new SimpleDateFormat(DATE_FORMAT_FULL).format(new Date());
        ResultVix rv = resultVixRepo.findByActiveTrueAndName(vix.getName());
        if (rv == null) {
            rv = new ResultVix(); rv.setName(vix.getName());
            rv.setEntryTime(testFlag ? formatDateTime(vix.getTimestamp()) : now);
            if (testFlag) rv.setEntryPrice(vix.getOpen());
            rv.setActive(true); rv.setType(type);
            Token token = triggerEntryOrder(strategy, type, rv, "Y".equals(strategy.getLive()));
            if (token != null) {
                rv.setToken(token.getToken()); rv.setSymbol(token.getSymbol());
                rv.setEntryPrice(token.getCurrentPrice());
            }
            resultVixRepo.save(rv);
            if ("Y".equalsIgnoreCase(strategy.getAlert())) notifyTelegram(type);
        } else if (!type.equalsIgnoreCase(rv.getType())) {
            Token token = triggerExitOrder(rv, "Y".equals(strategy.getLive()));
            rv.setExitPrice(testFlag ? vix.getOpen() : token.getCurrentPrice());
            rv.setExitTime(testFlag ? formatDateTime(vix.getTimestamp()) : now);
            rv.setPoints(calculatePoints(rv));
            rv.setActive(false);
            resultVixRepo.save(rv);
        }
    }

    public void exitFromTrade(String name, String type) throws AddressException, MessagingException, IOException {
        ResultVix rv = resultVixRepo.findByActiveTrueAndName(name);
        if (rv != null) {
            String now = new SimpleDateFormat(DATE_FORMAT_FULL).format(new Date());
            int h = NIFTY.equalsIgnoreCase(name) ? 15 : (SILVERM.equalsIgnoreCase(name) ? 23 : 0);
            if (isToday(now) && IsExit(now, h, 20)) {
                triggerExitOrder(rv, true);
                rv.setExitPrice(getCurrentPrice(name)); rv.setExitTime(now);
                rv.setPoints(calculatePoints(rv)); rv.setActive(false);
                resultVixRepo.save(rv);
            }
        }
    }

    // ================= MATH & HELPERS =================

    public int findNearestMultiple(int number, int base) {
        int remainder = number % base;
        return remainder < base / 2 ? number - remainder : number + (base - remainder);
    }

    public int calculatePoints(ResultVix rv) {
        if (rv.getEntryPrice() == null || rv.getExitPrice() == null) return 0;
        return rv.getExitPrice().subtract(rv.getEntryPrice()).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    // ================= ENTITY FACTORIES =================

    public Vix buildVix(OHLC ohlc, String name) {
        Vix vix = new Vix();
        vix.setTimestamp(ohlc.getTimestamp()); vix.setClose(ohlc.getClose());
        vix.setHigh(ohlc.getHigh()); vix.setOpen(ohlc.getOpen()); vix.setLow(ohlc.getLow());
        vix.setName(name); vix.setVolume(ohlc.getVolume()); vix.setRange(ohlc.getRange());
        vix.setType(priceUtilService.getPriceType(ohlc.getOpen(), ohlc.getClose()));
        return vix;
    }

    public PricesIndex buildPricesIndex(OHLC ohlc, String name, String exchange) {
        PricesIndex pi = new PricesIndex();
        pi.setTimestamp(formatTime(ohlc.getTimestamp())); pi.setClose(ohlc.getClose());
        pi.setHigh(ohlc.getHigh()); pi.setOpen(ohlc.getOpen()); pi.setLow(ohlc.getLow());
        pi.setName(name); pi.setVolume(ohlc.getVolume()); pi.setRange(ohlc.getRange());
        pi.setType(priceUtilService.getPriceType(ohlc.getOpen(), ohlc.getClose()));
        pi.setExchange(exchange); return pi;
    }

    // ================= UTILITIES =================

    public void updateCandleData(List<Candlestick> list, String candleType) {
        if (list == null || list.isEmpty()) return;
        List<Long> ids = list.stream().map(Candlestick::getId).collect(Collectors.toList());
        Map<Long, Vix> vixMap = vixRepo.findAllById(ids).stream().collect(Collectors.toMap(Vix::getId, v -> v));
        List<Vix> toUpdate = new ArrayList<>();
        for (Candlestick c : list) {
            Vix vix = vixMap.get(c.getId());
            if (vix == null) continue;
            switch (candleType.toUpperCase()) {
                case "PSAR" -> vix.setPsar(c.getSignal());
                case "HEIKINACHI" -> { vix.setHeikinachi(c.getSignal()); vix.setCandleType(c.getCandleType()); }
                case "MA" -> { vix.setSmoothma(c.getSmoothMA()); vix.setMasignal(c.getMasignal()); }
                case "SUPER_TREND" -> { vix.setSuperTrend(c.getSuperTrend()); vix.setSupertrendSignal(c.getSuperTrendSignal()); }
                case "VWAP" -> { vix.setVwap(c.getVwap()); vix.setVwapSignal(c.getSignal()); }
            }
            toUpdate.add(vix);
        }
        vixRepo.saveAll(toUpdate);
    }

    public Token triggerEntryOrder(Strategy s, String t, ResultVix rv, boolean live) throws IOException, AddressException, MessagingException {
        StrategyDTO dto = strategyHelperService.getStrategyDetails(s.getName(), s.getExchange());
        dto = getNameAndTradingSymbol(dto, t);
        return placeOrder(dto, t, "B", live);
    }

    public Token triggerExitOrder(ResultVix rv, boolean live) {
        StrategyDTO dto = new StrategyDTO(); dto.setName(rv.getName()); dto.setTradingsymbol(rv.getSymbol());
        String type = rv.getType().equalsIgnoreCase(BUY) ? Constants.TRANSACTION_TYPE_SELL : Constants.TRANSACTION_TYPE_BUY;
        return placeOrder(dto, type, "S", live);
    }

    public Token placeOrder(StrategyDTO dto, String txType, String ftType, boolean live) {
        SmartConnect sc = angelOne.signIn(); Token t = new Token();
        Indexes idx = indexesRepo.findByNameAndSymbol(dto.getName(), dto.getTradingsymbol());
        if (idx != null) {
            t.setExch_seg(idx.getExchange()); t.setToken(idx.getToken()); t.setSymbol(idx.getSymbol()); t.setQuantity(idx.getLotsize());
            t.setCurrentPrice(angelOneService.getcurrentPrice(sc, idx.getExchange(), idx.getSymbol(), idx.getToken()));
        }
        return t;
    }

    public StrategyDTO getNameAndTradingSymbol(StrategyDTO strategy, String type) {
        String key = strategy.getName().trim().toUpperCase();
        int interval = (NIFTY.equalsIgnoreCase(key) || CRUDEOILM.equalsIgnoreCase(key)) ? 50 : 1000;
        BigDecimal cp = getCurrentPrice(strategy.getName());
        int strike = findNearestMultiple(cp.intValue(), interval);
        String opt = BUY.equalsIgnoreCase(type) ? "CE" : "PE";
        if (NIFTY.equalsIgnoreCase(key)) strike += "CE".equalsIgnoreCase(opt) ? -150 : 150;
        strategy.setTradingsymbol(String.format("%s%s%d%s", strategy.getName(), strategy.getExpiry(), strike, opt));
        return strategy;
    }

    public Strategy getTokenDetails(String name, String exchange) {
        StrategyDTO dto = strategyHelperService.getStrategyDetails(name, exchange);
        return strategyHelperService.getChart(dto.getSymbol(), dto.getTradingsymbol(), dto.getLive());
    }

    public List<Candlestick> getValuesAsList(String name) {
        return vixRepo.findByName(name).stream().map(v -> {
            Candlestick c = new Candlestick(v.getOpen(), v.getHigh(), v.getLow(), v.getClose(), v.getId(), null, null, null);
            c.setVolume(v.getVolume() != null ? v.getVolume() : BigDecimal.ZERO); return c;
        }).collect(Collectors.toList());
    }

    public void readCandle(Indexes idx, String type, boolean test, String tf, String name, String from, String to, String tbl) {
        if ("HEIKIN_PSAR".equalsIgnoreCase(tbl)) {
            JSONArray res = getJsonDetails(idx, from, to, tf);
            List<Vix> batch = new ArrayList<>();
            for (int j = 0; j < res.length(); j++) {
                OHLC o = getOHLC(res.getJSONArray(j)); if (o != null) batch.add(buildVix(o, name));
            }
            if (!batch.isEmpty()) vixRepo.saveAll(batch);
        }
    }

    public BigDecimal getCurrentPrice(String name) {
        try { Strategy s = strategyRepo.findByName(name); return angelOneService.getcurrentPrice(angelOne.signIn(), s.getExchange(), s.getTradingsymbol(), s.getToken()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    public void lookForExecutedOrder(String name, String type, Vix vix, boolean testFlag) {
        ResultVix rv = resultVixRepo.findByActiveTrueAndName(name); if (rv == null) return;
        BigDecimal cp = testFlag ? vix.getClose() : getCurrentPrice(name);
        BigDecimal target = CRUDEOILM.equalsIgnoreCase(name) ? MCX_TARGET : NIFTY_TARGET;
        BigDecimal sl = CRUDEOILM.equalsIgnoreCase(name) ? MCX_SL : NIFTY_SL;
        BigDecimal move = cp.subtract(rv.getEntryPrice());
        if (move.compareTo(target) >= 0 || move.compareTo(sl.negate()) <= 0) {
            triggerExitOrder(rv, true); rv.setActive(false); resultVixRepo.save(rv);
        }
    }

    private void notifyTelegram(String msg) { try { telegramService.sendMessage(msg); } catch (Exception ignored) {} }
    
    public String getDate(String timeline, String type, int interval) { 
        LocalDate d = timeline.equalsIgnoreCase("FROM") ? NSEWorkingDays.getLastWorkingDay(LocalDate.now()) : LocalDate.now();
        return d.toString().concat(priceUtilService.getHourAndMinutes(timeline, interval, type)); 
    }

    public boolean buyEntrySignal(Vix vix) { return BUY.equalsIgnoreCase(vix.getHeikinachi()) && BUY.equalsIgnoreCase(vix.getPsar()) && BUY.equalsIgnoreCase(vix.getSupertrendSignal()); }
    public boolean sellEntrySignal(Vix vix) { return SELL.equalsIgnoreCase(vix.getHeikinachi()) && SELL.equalsIgnoreCase(vix.getPsar()) && SELL.equalsIgnoreCase(vix.getSupertrendSignal()); }
    public boolean buyExitSignal(Vix vix) { return vix.getHeikinachi().equalsIgnoreCase(BUY) && vix.getPsar().equalsIgnoreCase(BUY); }
    public boolean sellExitSignal(Vix vix) { return vix.getHeikinachi().equalsIgnoreCase(SELL) && vix.getPsar().equalsIgnoreCase(SELL); }
    public boolean compareHeikinAchiAndPsarCandle(List<Vix> list, int i) { return i < list.size() && list.get(i).getPsar().equalsIgnoreCase(list.get(i).getHeikinachi()); }
    public static boolean isToday(String ts) { return LocalDateTime.parse(ts, DateTimeFormatter.ofPattern(DATE_FORMAT_FULL)).toLocalDate().equals(LocalDate.now()); }
    public boolean IsExit(String in, int h, int m) { return LocalDateTime.parse(in, DateTimeFormatter.ofPattern(DATE_FORMAT_FULL)).toLocalTime().isAfter(LocalTime.of(h, m)); }
    public String formatTime(String in) { return OffsetDateTime.parse(in).withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME); }
    public static String formatDateTime(String ds) { return OffsetDateTime.parse(ds).format(DateTimeFormatter.ofPattern(DATE_FORMAT_SHORT)); }
}