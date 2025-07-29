package com.crumbs.trade.entity;



import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "Expiry")
public class Expiry {
	@Id
	@Column(name="id", nullable = false, unique = true)
	@GeneratedValue(strategy= GenerationType.IDENTITY)
	Long id;
	@Column(name="name")
	String name;
	@Column(name="expirydate")
	String expirydate;
	@Column(name="active")
	String active;
	@Column(name="roundOff")
	int roundOff;
	@Column(name="count")
	int count;
}
