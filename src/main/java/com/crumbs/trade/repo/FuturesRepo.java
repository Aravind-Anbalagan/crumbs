package com.crumbs.trade.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.Futures;

@Repository
public interface FuturesRepo extends JpaRepository<Futures, Long> {

    // Uses default JpaRepository methods:
    // findAll()
    // save()
    // saveAll()
    // findById()
    // delete()
}
