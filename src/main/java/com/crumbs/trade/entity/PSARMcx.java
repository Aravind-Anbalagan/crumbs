package com.crumbs.trade.entity;

import java.math.BigDecimal;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "psarmcx")
public class PSARMcx {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.IDENTITY)
	Long id;
	@Column(name="timestamp")
	String timestamp;
	@Column(name="currentprice")
	BigDecimal currentprice;
	@Column(name="psarPrice")
	BigDecimal psarPrice;
	@Column(name="timeframe")
	BigDecimal timeframe;
	@Column(name="high")
	BigDecimal high;
	@Column(name="low")
	BigDecimal low;
	@Column(name="type")
	String type;
	@Column(name = "name")
	String name;
	@Column(name="open")
	BigDecimal open;
	
}


