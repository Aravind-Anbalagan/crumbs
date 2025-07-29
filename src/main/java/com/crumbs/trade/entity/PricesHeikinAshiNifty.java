package com.crumbs.trade.entity;

import java.math.BigDecimal;

import jakarta.persistence.*;



@lombok.Data
@Entity
@Table(name = "priceheikinashinifty")
public class PricesHeikinAshiNifty {
	@Id
	@Column(name = "id", nullable = false, unique = true)
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;
	@Column(name = "timestamp")
	String timestamp;
	@Column(name = "open")
	BigDecimal open;
	@Column(name = "high")
	BigDecimal high;
	@Column(name = "low")
	BigDecimal low;
	@Column(name = "close")
	BigDecimal close;
	@Column(name = "volume")
	BigDecimal volume;
	@Column(name = "timeframe")
	String timeframe;
	@Column(name = "name")
	String name;
	@Column(name = "type")
	String type;
	@Column(name = "range")
	BigDecimal range;
	@Column(name = "percentage")
	BigDecimal percentage;
	@Column(name = "signal")
	String signal;
	@Column(name = "result")
	String result;
	@Column(name="currentprice")
	BigDecimal currentprice;
}
