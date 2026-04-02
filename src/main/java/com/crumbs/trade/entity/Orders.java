package com.crumbs.trade.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
	private LocalDateTime createdOn;
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
	@Column(name="exchange")
	String exchange;
	@Column(name="quantity")
	int quantity;
	private String optionType; // CE / PE
    private String side;       // BUY / SELL

    private String tradePhase; // ENTRY / EXIT / FLIP / STOP
    private String status;     // OPEN / CLOSED
    
    @Column(name = "trade_cycle_id")
    private String tradeCycleId;
    
    @Column(name = "is_reversal")
    private Boolean reversal;
    
    @Column(name = "closed_on")
    private LocalDateTime closedOn;

    @Column(name = "strike")
    private Integer strike;
  
}
