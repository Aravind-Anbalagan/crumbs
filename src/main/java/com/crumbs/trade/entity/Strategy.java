package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.util.Date;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "STRATEGY")
public class Strategy {

	@Id
	@Column(name="id", nullable = false, unique = true)
	Long id;
	@Column(name="symbol")
	String symbol;
	@Column(name="symbol1")
	String symbol1;
	@Column(name="name")
	String name;
	@Column(name="active")
	String active;
	@Column(name="execute")
	String execute;
	@Column(name="exchange")
	String exchange;
	@Column(name="token")
	String token;
	@Column(name="tradingsymbol")
	String tradingsymbol;
	@Column(name="points")
	int points;
	@Column(name="candlestick")
	int candlestick;
	@Column(name="expiry")
	String expiry;
	@Column(name="live")
	String live;
	@Column(name="papertrade")
	String papertrade;
	@Column(name="dayCandle")
	String dayCandle;
	
	public String getDayCandle() {
		return dayCandle;
	}
	public void setDayCandle(String dayCandle) {
		this.dayCandle = dayCandle;
	}
	public String getPapertrade() {
		return papertrade;
	}
	public void setPapertrade(String papertrade) {
		this.papertrade = papertrade;
	}
	public String getLive() {
		return live;
	}
	public void setLive(String live) {
		this.live = live;
	}
	public Long getId() {
		return id;
	}
	public void setId(Long id) {
		this.id = id;
	}
	public String getSymbol() {
		return symbol;
	}
	public void setSymbol(String symbol) {
		this.symbol = symbol;
	}
	public String getSymbol1() {
		return symbol1;
	}
	public void setSymbol1(String symbol1) {
		this.symbol1 = symbol1;
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getActive() {
		return active;
	}
	public void setActive(String active) {
		this.active = active;
	}
	public String getExecute() {
		return execute;
	}
	public void setExecute(String execute) {
		this.execute = execute;
	}
	public String getExchange() {
		return exchange;
	}
	public void setExchange(String exchange) {
		this.exchange = exchange;
	}
	public String getToken() {
		return token;
	}
	public void setToken(String token) {
		this.token = token;
	}
	public String getTradingsymbol() {
		return tradingsymbol;
	}
	public void setTradingsymbol(String tradingsymbol) {
		this.tradingsymbol = tradingsymbol;
	}
	public int getPoints() {
		return points;
	}
	public void setPoints(int points) {
		this.points = points;
	}
	public int getCandlestick() {
		return candlestick;
	}
	public void setCandlestick(int candlestick) {
		this.candlestick = candlestick;
	}
	public String getExpiry() {
		return expiry;
	}
	public void setExpiry(String expiry) {
		this.expiry = expiry;
	}

	
	
}
