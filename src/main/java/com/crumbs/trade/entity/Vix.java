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
	@Column(name="macd")
	String macd;
	@Column(name="active")
	String active;
	@Column(name="candleType")
	String candleType;
	@Column(name="trendSignal")
	String trendSignal;
	@Column(name="zigzagSignal")
	String zigzagSignal;
	@Column(name="trendDirection")
	String trendDirection;
	@Column(name="zigzagDirection")
	String zigzagDirection;

}