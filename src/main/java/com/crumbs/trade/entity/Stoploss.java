package com.crumbs.trade.entity;

import java.math.BigDecimal;


import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "stoploss")
public class Stoploss {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.AUTO)
	Long id;
	@Column(name="startdate")
	String startdate;
	@Column(name="enddate")
	String enddate;
	@Column(name="min")
	BigDecimal min;
	@Column(name="max")
	BigDecimal max;
	@Column(name="type")
	String type;
	@Column(name="name")
	String name;
}
