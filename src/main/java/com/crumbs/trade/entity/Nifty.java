package com.crumbs.trade.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "FO_STOCKS")
@Data
public class Nifty {
    
    @Id
    @Column(name="id", nullable = false, unique = true)
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private Long id;
    
    @Column(name="name")
    private String name;

    @Column(name="is_active")
    private Boolean isActive; 
    
    @Column(name="token")
    private String token;
}