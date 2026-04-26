package com.crumbs.trade.entity;

import java.math.BigDecimal;

import jakarta.persistence.*;

@lombok.Data
@Entity
@Table(name = "vix")
public class Vix {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.IDENTITY)
	Long id;
	@Column(name="timestamp")
	String timestamp;
	@Column(name="open")
	BigDecimal open;
	@Column(name="high")
	BigDecimal high;
	@Column(name="low")
	BigDecimal low;
	@Column(name="close")
	BigDecimal close;
	@Column(name="timeframe")
	String timeframe;
	@Column(name="name")
	String name;
	@Column(name="type")
	String type;
	@Column(name="volume")
	BigDecimal volume;
	@Column(name="range")
	BigDecimal range;
	@Column(name="heikinachi")
	String heikinachi;
	@Column(name="psar")
	String psar;
	@Column(name="smoothma")
	BigDecimal smoothma;
	@Column(name="masignal")
	String masignal;
	@Column(name="active")
	String active;
	@Column(name="candleType")
	String candleType;
	@Column(name="superTrend")
	BigDecimal superTrend;
	@Column(name="supertrendSignal")
	String supertrendSignal;
	@Column(precision = 19, scale = 6)
	private BigDecimal vwap;
	// Optional: if you plan to store signal (e.g. "BUY"/"SELL")
	private String vwapSignal;

	// =========================================================
	// NEW: Dual EMA Crossover Strategy Fields
	// =========================================================
	@Column(name="fastEma", precision = 19, scale = 8)
	BigDecimal fastEma;

	@Column(name="slowEma", precision = 19, scale = 8)
	BigDecimal slowEma;

	@Column(name="crossoverEvent")
	String crossoverEvent;
}