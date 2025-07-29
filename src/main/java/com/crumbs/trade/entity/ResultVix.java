package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;



import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "resultvix")
public class ResultVix {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.AUTO)
	Long id;
	@Column(name="name")
	String name;
	@Column(name="active")
	String active;
	@CreatedDate
	@Column(name="createdDate")
	Date  createdDate;
	@LastModifiedDate
	@Column(name="modifiedDate")
	Date  modifiedDate;
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
	@Column(name="maxHigh")
	int maxHigh;
	@Column(name="maxLow")
	int maxLow;
	@Column(name="exchange")
	String exchange;
	@Column(name="token")
	String token;
	@Column(name="symbol")
	String symbol;
	
}

