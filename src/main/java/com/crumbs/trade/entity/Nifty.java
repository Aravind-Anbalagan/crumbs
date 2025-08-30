package com.crumbs.trade.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "FO_STOCKS")
public class Nifty {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.IDENTITY)
	Long id;
	@Column(name="name")
	String name;
}
