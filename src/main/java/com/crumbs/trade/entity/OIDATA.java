package com.crumbs.trade.entity;

import java.math.BigDecimal;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "OIDATA")
public class OIDATA {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.IDENTITY)
	Long id;
	@Column(name="name")
	String name;
	@Column(name="strikePrice")
	BigDecimal strikePrice;
	@Lob
	@Column(name="callOIChange")
	String callOIChange;
	@Lob
	@Column(name="callOI")
	String callOI;
	@Lob
	@Column(name="callLTP")
	String callLTP;
	@Lob
	@Column(name="callTrend")
	String callTrend;
	@Lob
	@Column(name="putOIChange")
	String putOIChange;
	@Lob
	@Column(name="putOI")
	String putOI;
	@Lob
	@Column(name="putLTP")
	String putLTP;
	@Lob
	@Column(name="putTrend")
	String putTrend;
	@Column(name="spot")
	String spot;
	@Column(name="putSignal")
	String putSignal;
	@Column(name="callSignal")
	String callSignal;
	@Column(name="expiry")
	String expiry;

}