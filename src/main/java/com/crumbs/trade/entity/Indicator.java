package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "indicator", indexes = {
	    @Index(name = "idx_heikin_day_psar", columnList = "heikinAshiDay, psarFlagDay"),
	    @Index(name = "idx_heikin_weekly_psar", columnList = "heikinAshiWeekly, psarFlagWeekly"),
	    @Index(name = "idx_heikin_hourly_psar", columnList = "heikinAshiHourly, psarFlagHourly")
	})
public class Indicator {
	@Id
	@Column(name = "id", nullable = false, unique = true)
	@GeneratedValue(strategy = GenerationType.AUTO)
	Long id;
	@Column(name = "name")
	String name;
	@Column(name = "timeFrame")
	String timeFrame;
	@Column(name = "dailysupport", length = 65535)
	String dailysupport;
	@Column(name = "dailyresistance")
	String dailyresistance;
	@Column(name = "token")
	String token;
	@Column(name = "tradingSymbol")
	String tradingSymbol;
	@Column(name = "exchange")
	String exchange;
	@Column(name = "dailysignal")
	String dailysignal;
	@Column(name = "avgrange")
	BigDecimal avgrange;
	@Column(name = "currentPrice")
	BigDecimal currentPrice;
	@Column(name = "openPrice")
	BigDecimal openPrice;
	@Column(name = "prevdaycloseprice")
	BigDecimal prevdaycloseprice;
	@Column(name = "prevdayclosepriceflag")
	String prevdayclosepriceflag;
	@Column(name = "fifty2_weeklow")
	BigDecimal fifty2_weeklow;
	@Column(name = "fifty2_weekhigh")
	BigDecimal fifty2_weekhigh;
	@Column(name = "fifty2week_flag")
	String fifty2week_flag;
	@CreatedDate
	@Column(name = "createdDate")
	LocalDateTime createdDate;
	@LastModifiedDate
	@Column(name = "modifiedDate")
	LocalDateTime modifiedDate;
	@Column(name = "last3daycandlehigh")
	String last3daycandlehigh;
	@Column(name = "first3FiveMinsCandle")
	String first3FiveMinsCandle;
	@Column(name = "last3daycandlelow")
	String last3daycandlelow;
	@Column(name = "last3daycandleflag")
	String last3daycandleflag;
	@Column(name = "weeklysupport", length = 65535)
	String weeklysupport;
	@Column(name = "weeklyresistance")
	String weeklyresistance;
	@Column(name = "weeklysignal")
	String weeklysignal;
	@Column(name = "mailsent")
	String mailsent;
	@Column(name = "result")
	String result;
	@Column(name = "cpr")
	String cpr;
	@Column(name = "cprflag")
	String cprflag;
	@Column(name = "fourHoursupport", length = 65535)
	String fourHoursupport;
	@Column(name = "fourHourresistance")
	String fourHourresistance;
	@Column(name = "fourHoursignal")
	String fourHoursignal;
	@Column(name = "Hoursupport", length = 65535)
	String Hoursupport;
	@Column(name = "Hourresistance")
	String Hourresistance;
	@Column(name = "Hoursignal")
	String Hoursignal;
	@Column(name = "dailyopenandcloseissame")
	String dailyopenandcloseissame;
	@Column(name = "monthlysupport", length = 65535)
	String monthlysupport;
	@Column(name = "monthlyresistance")
	String monthlyresistance;
	@Column(name = "monthlysignal")
	String monthlysignal;
	@Column(name = "dailyRSI")
	BigDecimal dailyRSI;
	@Column(name = "weeklyRSI")
	BigDecimal weeklyRSI;
	@Column(name = "movingavg200")
	BigDecimal movingavg200;
	@Column(name = "movingavg200Flag")
	BigDecimal movingavg200Flag;
	@Column(name = "movingavg50")
	BigDecimal movingavg50;
	@Column(name = "movingavg50Flag")
	BigDecimal movingavg50Flag;
	@Column(name = "movingavg20")
	BigDecimal movingavg20;
	@Column(name = "movingavg20Flag")
	BigDecimal movingavg20Flag;
	@Column(name = "bollingerband")
	String bollingerband;
	@Column(name = "bollingerflag")
	String bollingerflag;
	@Column(name = "psarFlagDay")
	String psarFlagDay;
	@Column(name = "heikinAshiDay")
	String heikinAshiDay;
	@Column(name = "buysl")
	BigDecimal buysl;
	@Column(name = "sellsl")
	BigDecimal sellsl;
	@Column(name = "psarFlagWeekly")
	String psarFlagWeekly;
	@Column(name = "heikinAshiWeekly")
	String heikinAshiWeekly;

	@Column(name = "hourlybuysl")
	BigDecimal hourlybuysl;
	@Column(name = "hourlysellsl")
	BigDecimal hourlysellsl;
	@Column(name = "psarFlagHourly")
	String psarFlagHourly;
	@Column(name = "heikinAshiHourly")
	String heikinAshiHourly;
	@Column(name = "last3HourCandleLow")
	String last3Hourcandlelow;
	@Column(name = "last3HourCandleFlag")
	String last3HourCandleFlag;
	@Column(name = "last3HourCandleHigh")
	String last3HourCandleHigh;
	@Column(name = "hourlySignal")
	String hourlySignal;
	@Column(name = "volume")
	String volume;
	@Column(name = "volumeFlag")
	String volumeFlag;
	@Column(name = "intraday")
	String intraday;
	@Column(name = "pivot")
	String pivot;
	@Column(name = "pivotFlag")
	String pivotFlag;
	// Daily
	@Column(name = "dailyPriceActionSupport")
	String dailyPriceActionSupport;
	@Column(name = "dailyPriceActionResistance")
	String dailyPriceActionResistance;
	@Column(name = "dailyPriceActionFlag")
	boolean dailyPriceActionFlag;
	@Column(name = "daily_sr_signal")
	String daily_sr_signal;
	@Column(name = "daily_sr_trend")
	String daily_sr_trend;
	@Column(name = "daily_sr_confidence")
	String daily_sr_confidence;
	@Column(name = "daily_sr_reason")
	String daily_sr_reason;
	@Column(name = "daily_fiboSupport")
	String daily_fiboSupport;
	@Column(name = "daily_fiboResistance")
	String daily_fiboResistance;
	@Column(name = "daily_fiboFlag")
	boolean daily_fiboFlag;
	@Column(name = "daily_fibo_signal")
	String daily_fibo_signal;
	@Column(name = "daily_fibo_trend")
	String daily_fibo_trend;
	@Column(name = "daily_fibo_confidence")
	String daily_fibo_confidence;
	@Column(name = "daily_fibo_reason")
	String daily_fibo_reason;
	@Column(name = "daily_aiSignal")
	String daily_aiSignal;
	@Column(name = "daily_aiReason")
	String daily_aiReason;
	@Column(name = "daily_aiConfidence")
	String daily_aiConfidence;

	// weekly
	// Daily
	@Column(name = "weeklyPriceActionSupport")
	String weeklyPriceActionSupport;
	@Column(name = "weeklyPriceActionResistance")
	String weeklyPriceActionResistance;
	@Column(name = "weeklyPriceActionFlag")
	boolean weeklyPriceActionFlag;
	@Column(name = "weekly_sr_signal")
	String weekly_sr_signal;
	@Column(name = "weekly_sr_trend")
	String weekly_sr_trend;
	@Column(name = "weekly_sr_confidence")
	String weekly_sr_confidence;
	@Column(name = "weekly_sr_reason")
	String weekly_sr_reason;
	@Column(name = "weekly_fiboSupport")
	String weekly_fiboSupport;
	@Column(name = "weekly_fiboResistance")
	String weekly_fiboResistance;
	@Column(name = "weekly_fiboFlag")
	boolean weekly_fiboFlag;
	@Column(name = "weekly_fibo_signal")
	String weekly_fibo_signal;
	@Column(name = "weekly_fibo_trend")
	String weekly_fibo_trend;
	@Column(name = "weekly_fibo_confidence")
	String weekly_fibo_confidence;
	@Column(name = "weekly_fibo_reason")
	String weekly_fibo_reason;
	@Column(name = "weekly_aiSignal")
	String weekly_aiSignal;
	@Column(name = "weekly_aiReason")
	String weekly_aiReason;
	@Column(name = "weekly_aiConfidence")
	String weekly_aiConfidence;
	
	//Combine
	@Column(name = "combine_signal")
	private String combineSignal;

	@Column(name = "combine_confidence")
	private String combineConfidence;

	@Column(name = "combine_reason_summary", columnDefinition = "TEXT")
	private String combineReasonSummary;

	@Column(name = "combine_detailed_reason", columnDefinition = "TEXT")
	private String combineDetailedReason;

	@Column(name = "combine_buy_votes")
	private Integer combineBuyVotes;

	@Column(name = "combine_sell_votes")
	private Integer combineSellVotes;

	@Column(name = "combine_hold_votes")
	private Integer combineHoldVotes;

}
