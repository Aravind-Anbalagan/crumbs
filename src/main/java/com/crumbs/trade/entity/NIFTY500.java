package com.crumbs.trade.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "NIFTY500")
@Data  // ✅ Lombok: generates getters, setters, toString, equals, hashCode
public class NIFTY500 {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, unique = true)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;
    
    @Column(name = "sector", nullable = false)
    private String sector;
    
    @Column(name = "industry", nullable = false)
    private String industry;

  
}
