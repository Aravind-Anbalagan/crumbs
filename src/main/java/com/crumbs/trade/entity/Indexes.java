package com.crumbs.trade.entity;


import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "Indexes")
public class Indexes {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.IDENTITY)
	Long id;
	@Column(name="name")
	String name;
	@Column(name="token")
	String token;
	@Column(name="exchange")
	String exchange;
	@Column(name="fromDate")
	String fromDate;
	@Column(name="toDate")
	String toDate;
	@Column(name="timeFrame")
	String timeFrame;

	@Column(name="volume")
	String volume;
	@Column(name="symbol")
	String symbol;
	@Column(name="active")
	String active;
	@Column(name="strike")
	String strike;
	@Column(name="lotsize")
	int lotsize;
	@Column(name="expiry")
	String expiry;
}
