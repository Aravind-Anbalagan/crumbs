package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "Signals")
public class Signals {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.AUTO)
	Long id;
	@Column(name="name")
	String name;
	@Column(name="createdAt")
	String createdAt;
	@Column(name="priceAction")
	String priceAction;
	@Column(name="fibo")
	String fibo;
	@Column(name="finals")
	String finals;
	

}
