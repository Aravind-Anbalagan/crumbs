package com.crumbs.trade.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "CPR")
public class CPR {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.AUTO)
	Long id;
	@Column(name="name")
	String name;
	@Column(name="date")
	String date;
	@Column(name="pivot")
	private BigDecimal pivot;
	@Column(name="top")
	private BigDecimal top;
	@Column(name="bottom")
	private BigDecimal bottom;
	@Column(name="high")
	private BigDecimal high;
	@Column(name="low")
	private BigDecimal low;
}
