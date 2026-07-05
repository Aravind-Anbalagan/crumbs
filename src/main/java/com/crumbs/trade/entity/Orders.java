package com.crumbs.trade.entity;

import java.math.BigDecimal;
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
	@Column(name="name")
	String name;
	@Column(name="type")
	String type;
	@Column(name="active")
	int active;
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

    @Column(name = "askprice", precision = 12, scale = 2)
    private BigDecimal askPrice;   // entry price

    @Column(name = "exitprice", precision = 12, scale = 2)
    private BigDecimal exitPrice;

    @Column(name = "sl", precision = 12, scale = 2)
    private BigDecimal sl;

    @Column(name = "pl", precision = 12, scale = 2)
    private BigDecimal pl;         // target initially, pnl after close

    @Column(name = "breakeven", precision = 12, scale = 2)
    private BigDecimal breakeven;

    @Column(name = "strike", precision = 12, scale = 2)
    private BigDecimal strike;     // level price
    
    @Column(name = "target", precision = 12, scale = 2)
    private BigDecimal target;
    
    @Column(name = "exit_reason")
    private String exitReason;
    
    @Column(name = "target_spot_price", precision = 19, scale = 2)
    private BigDecimal targetSpotPrice;
  
}
