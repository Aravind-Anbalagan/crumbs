package com.crumbs.trade.entity;

import java.math.BigDecimal;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "OI")
public class OI {
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
	@Lob
	@Column(name="putSignal")
	String putSignal;
	@Lob
	@Column(name="callSignal")
	String callSignal;
	@Column(name="expiry")
	String expiry;
	@Lob
	@Column(name="totalVolume")
	String totalVolume;
	

}
