package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.util.Date;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "resultmcx")
public class ResultMcx {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.AUTO)
	Long id;
	@Column(name="name")
	String name;
	@Column(name="token")
	String token;
	@Column(name="active")
	String active;
	@Column(name="tradingSymbol")
	String tradingSymbol;
	@Column(name="exchange")
	String exchange;
	@CreatedDate
	@Column(name="createdDate")
	Date  createdDate;
	@LastModifiedDate
	@Column(name="modifiedDate")
	Date  modifiedDate;
	@Column(name="currentltp")
	BigDecimal currentltp;
	@Column(name="comment")
	String comment;
	@Column(name="type")
	String type;
	@Column(name="result")
	String result;
	@Column(name="timestamp")
	String timestamp;
	@Column(name = "entryTime")
	String entryTime;
	@Column(name = "exitTime")
	String exitTime;
	@Column(name="entryPrice")
	BigDecimal entryPrice;
	@Column(name="exitPrice")
	BigDecimal exitPrice;
	@Column(name="points")
	int points;
	@Column(name="lotSize")
	int lotSize;
	
}

