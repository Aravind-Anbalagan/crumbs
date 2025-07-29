package com.crumbs.trade.entity;

import java.math.BigDecimal;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "ORDERS")
public class Orders {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.AUTO)
	Long id;
	@Column(name="orderid")
	String orderid;
	@Column(name="createdon")
	String createdOn;
	@Column(name="symbol")
	String symbol;
	@Column(name="token")
	String token;
	@Column(name="askprice")
	int askPrice;
	@Column(name="exitprice")
	int exitPrice;
    @Column(name="sl")
	int sl;
	@Column(name="pl")
	int pl;
	@Column(name="name")
	String name;
	@Column(name="type")
	String type;
	@Column(name="active")
	int active;
	@Column(name="breakeven")
	int breakeven;
	@Column(name="signal")
	String signal;
}
