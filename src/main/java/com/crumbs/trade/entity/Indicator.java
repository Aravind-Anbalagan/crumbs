package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@DynamicUpdate
@EntityListeners(AuditingEntityListener.class)
@Table(name = "indicator")
public class Indicator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name") private String name;
    @Column(name = "timeFrame") private String timeFrame;

    @Column(name = "dailysupport", columnDefinition = "TEXT") private String dailysupport;
    @Column(name = "dailyresistance") private String dailyresistance;

    @Column(name = "token") private String token;
    @Column(name = "tradingSymbol") private String tradingSymbol;
    @Column(name = "exchange") private String exchange;
    @Column(name = "dailysignal") private String dailysignal;

    @Column(name = "avgrange") private BigDecimal avgrange;
    @Column(name = "currentPrice") private BigDecimal currentPrice;
    @Column(name = "executedPrice") private BigDecimal executedPrice;
    @Column(name = "openPrice") private BigDecimal openPrice;
    @Column(name = "prevdaycloseprice") private BigDecimal prevdaycloseprice;

    @Column(name = "prevdayclosepriceflag") private String prevdayclosepriceflag;

    @Column(name = "fifty2_weeklow") private BigDecimal fifty2_weeklow;
    @Column(name = "fifty2_weekhigh") private BigDecimal fifty2_weekhigh;
    @Column(name = "fifty2week_flag") private String fifty2week_flag;

    @CreatedDate @Column(name = "createdDate") private LocalDateTime createdDate;
    @LastModifiedDate @Column(name = "modifiedDate") private LocalDateTime modifiedDate;
    @Column(name = "executedDate") private LocalDateTime executedDate;

    @Column(name = "last3daycandlehigh") private String last3daycandlehigh;
    @Column(name = "first3FiveMinsCandle") private String first3FiveMinsCandle;
    @Column(name = "last3daycandlelow") private String last3daycandlelow;
    @Column(name = "last3daycandleflag") private String last3daycandleflag;

    @Column(name = "weeklysupport", columnDefinition = "TEXT") private String weeklysupport;
    @Column(name = "weeklyresistance") private String weeklyresistance;
    @Column(name = "weeklysignal") private String weeklysignal;

    @Column(name = "mailsent") private String mailsent;
    @Column(name = "result") private String result;
    @Column(name = "cpr") private String cpr;
    @Column(name = "cprflag") private String cprflag;

    @Column(name = "fourHoursupport", columnDefinition = "TEXT") private String fourHoursupport;
    @Column(name = "fourHourresistance") private String fourHourresistance;
    @Column(name = "fourHoursignal") private String fourHoursignal;

    @Column(name = "Hoursupport", columnDefinition = "TEXT") private String Hoursupport;
    @Column(name = "Hourresistance") private String Hourresistance;
    @Column(name = "Hoursignal") private String Hoursignal;

    @Column(name = "dailyopenandcloseissame") private String dailyopenandcloseissame;

    @Column(name = "monthlysupport", columnDefinition = "TEXT") private String monthlysupport;
    @Column(name = "monthlyresistance") private String monthlyresistance;
    @Column(name = "monthlysignal") private String monthlysignal;

    @Column(name = "dailyRSI") private BigDecimal dailyRSI;
    @Column(name = "weeklyRSI") private BigDecimal weeklyRSI;

    @Column(name = "movingavg200") private BigDecimal movingavg200;
    @Column(name = "movingavg200Flag") private BigDecimal movingavg200Flag;
    @Column(name = "movingavg50") private BigDecimal movingavg50;
    @Column(name = "movingavg50Flag") private BigDecimal movingavg50Flag;
    @Column(name = "movingavg20") private BigDecimal movingavg20;
    @Column(name = "movingavg20Flag") private BigDecimal movingavg20Flag;

    @Column(name = "movingavg21") private BigDecimal movingavg21;
    @Column(name = "movingavg9") private BigDecimal movingavg9;

    @Column(name = "maHierarchyFlag") private String maHierarchyFlag;
    @Column(name = "bollingerband") private String bollingerband;
    @Column(name = "bollingerflag") private String bollingerflag;

    @Column(name = "psarFlagDay") private String psarFlagDay;
    @Column(name = "heikinAshiDay") private String heikinAshiDay;

    @Column(name = "buysl") private BigDecimal buysl;
    @Column(name = "sellsl") private BigDecimal sellsl;

    @Column(name = "psarFlagWeekly") private String psarFlagWeekly;
    @Column(name = "heikinAshiWeekly") private String heikinAshiWeekly;

    @Column(name = "hourlybuysl") private BigDecimal hourlybuysl;
    @Column(name = "hourlysellsl") private BigDecimal hourlysellsl;

    @Column(name = "psarFlagHourly") private String psarFlagHourly;
    @Column(name = "heikinAshiHourly") private String heikinAshiHourly;

    @Column(name = "last3HourCandleLow") private String last3Hourcandlelow;
    @Column(name = "last3HourCandleFlag") private String last3HourCandleFlag;
    @Column(name = "last3HourCandleHigh") private String last3HourCandleHigh;

    @Column(name = "hourlySignal") private String hourlySignal;

    @Column(name = "volume") private String volume;
    @Column(name = "volumeFlag") private String volumeFlag;
    @Column(name = "weeklyvolume") private String weeklyvolume;
    @Column(name = "weeklyvolumeFlag") private String weeklyvolumeFlag;

    @Column(name = "intraday") private String intraday;
    @Column(name = "pivot") private String pivot;
    @Column(name = "pivotFlag") private String pivotFlag;

    // TEXT fields (fixed)
    @Column(name = "dailyPriceActionSupport", columnDefinition = "TEXT") private String dailyPriceActionSupport;
    @Column(name = "dailyPriceActionResistance", columnDefinition = "TEXT") private String dailyPriceActionResistance;

    @Column(name = "dailyPriceActionFlag") private Boolean dailyPriceActionFlag;

    @Column(name = "daily_sr_signal") private String daily_sr_signal;
    @Column(name = "daily_sr_trend") private String daily_sr_trend;
    @Column(name = "daily_sr_confidence") private String daily_sr_confidence;

    @Column(name = "daily_sr_reason", columnDefinition = "TEXT") private String daily_sr_reason;

    @Column(name = "daily_fiboSupport") private String daily_fiboSupport;
    @Column(name = "daily_fiboResistance") private String daily_fiboResistance;

    @Column(name = "daily_fiboFlag") private Boolean daily_fiboFlag;

    @Column(name = "daily_fibo_signal") private String daily_fibo_signal;
    @Column(name = "daily_fibo_trend") private String daily_fibo_trend;
    @Column(name = "daily_fibo_confidence") private String daily_fibo_confidence;

    @Column(name = "daily_fibo_reason", columnDefinition = "TEXT") private String daily_fibo_reason;

    @Column(name = "daily_aiSignal") private String daily_aiSignal;
    @Column(name = "daily_aiReason", columnDefinition = "TEXT") private String daily_aiReason;
    @Column(name = "daily_aiConfidence") private String daily_aiConfidence;

    @Column(name = "weeklyPriceActionSupport", columnDefinition = "TEXT") private String weeklyPriceActionSupport;
    @Column(name = "weeklyPriceActionResistance", columnDefinition = "TEXT") private String weeklyPriceActionResistance;

    @Column(name = "weeklyPriceActionFlag") private Boolean weeklyPriceActionFlag;

    @Column(name = "weekly_sr_signal") private String weekly_sr_signal;
    @Column(name = "weekly_sr_trend") private String weekly_sr_trend;
    @Column(name = "weekly_sr_confidence") private String weekly_sr_confidence;

    @Column(name = "weekly_sr_reason", columnDefinition = "TEXT") private String weekly_sr_reason;

    @Column(name = "weekly_fiboSupport") private String weekly_fiboSupport;
    @Column(name = "weekly_fiboResistance") private String weekly_fiboResistance;

    @Column(name = "weekly_fiboFlag") private Boolean weekly_fiboFlag;

    @Column(name = "weekly_fibo_signal") private String weekly_fibo_signal;
    @Column(name = "weekly_fibo_trend") private String weekly_fibo_trend;
    @Column(name = "weekly_fibo_confidence") private String weekly_fibo_confidence;

    @Column(name = "weekly_fibo_reason", columnDefinition = "TEXT") private String weekly_fibo_reason;

    @Column(name = "weekly_aiSignal") private String weekly_aiSignal;
    @Column(name = "weekly_aiReason", columnDefinition = "TEXT") private String weekly_aiReason;
    @Column(name = "weekly_aiConfidence") private String weekly_aiConfidence;

    @Column(name = "combine_signal") private String combineSignal;
    @Column(name = "combine_confidence") private String combineConfidence;

    @Column(name = "combine_reason_summary", columnDefinition = "TEXT") private String combineReasonSummary;
    @Column(name = "combine_detailed_reason", columnDefinition = "TEXT") private String combineDetailedReason;

    @Column(name = "combine_buy_votes") private Integer combineBuyVotes;
    @Column(name = "combine_sell_votes") private Integer combineSellVotes;
    @Column(name = "combine_hold_votes") private Integer combineHoldVotes;

    @Column(name = "oneday") private String oneday;
    @Column(name = "oneweek") private String oneweek;
    @Column(name = "options") private String options;
    @Column(name = "sector") private String sector;
    @Column(name = "tradetype") private String tradetype;
    @Column(name = "lastExpiryLevel") private String lastExpiryLevel;

    @Column(name = "superTrendDaily") private BigDecimal superTrendDaily;
    @Column(name = "superTrendSignalDaily") private String superTrendSignalDaily;
    @Column(name = "superTrendVolatilityDaily") private String superTrendVolatilityDaily;
    @Column(name = "VwapDaily") private BigDecimal VwapDaily;
    @Column(name = "VwapSignalDaily") private String VwapSignalDaily;

    @Column(name = "superTrendWeekly") private BigDecimal superTrendWeekly;
    @Column(name = "superTrendSignalWeekly") private String superTrendSignalWeekly;
    @Column(name = "superTrendVolatilityWeekly") private String superTrendVolatilityWeekly;
    @Column(name = "VwapWeekly") private BigDecimal VwapWeekly;
    @Column(name = "VwapSignalWeekly") private String VwapSignalWeekly;

    @Column(name = "superTrendHourly") private BigDecimal superTrendHourly;
    @Column(name = "superTrendSignalHourly") private String superTrendSignalHourly;
    @Column(name = "superTrendVolatilityHourly") private String superTrendVolatilityHourly;
    @Column(name = "VwapHourly") private BigDecimal VwapHourly;
    @Column(name = "VwapSignalHourly") private String VwapSignalHourly;

    @Column(name = "cpr1H") private String cpr1H;
    @Column(name = "cpr1D") private String cpr1D;
    @Column(name = "cpr1W") private String cpr1W;

    @Column(name = "sl") private String sl;
}