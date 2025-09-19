package com.crumbs.trade.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "NIFTY500")
@Data  // ✅ Lombok: generates getters, setters, toString, equals, hashCode
@NoArgsConstructor // ✅ Required by JPA (no-arg constructor)
public class NIFTY500 {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, unique = true)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    // You can still add custom constructors if needed
    public NIFTY500(String name) {
        this.name = name;
    }
}
