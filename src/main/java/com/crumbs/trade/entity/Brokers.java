package com.crumbs.trade.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "BROKERS")
public class Brokers {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.IDENTITY)
	Long id;
	@Column(name="brokername")
	String brokername;
	@Column(name="username")
	String username;
	@Column(name="password")
	String password;
	@Column(name="apikey")
	String apikey;
	@Column(name="apisecret")
	String apisecret;
	@Column(name="totpsecret")
	String totpsecret;
	@Column(name="requestcode")
    String requestcode;
}
