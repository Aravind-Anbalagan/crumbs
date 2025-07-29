package com.crumbs.trade.entity;

import java.math.BigDecimal;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "Candle")
public class Candle {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.IDENTITY)
	Long id;
	@Column(name="name")
	String name;
	@Column(name="fromDate")
	String fromDate;
	@Column(name="toDate")
	String toDate;
	@Column(name="timeFrame")
	String timeFrame;
	@Column(name="supportdays")
	int supportdays;
	@Column(name="priceLimit")
	int priceLimit;
	@Column(name="active")
	String active;
	@Column(name="startTime")
	String startTime;
	@Column(name="endTime")
	String endTime;
	
}
