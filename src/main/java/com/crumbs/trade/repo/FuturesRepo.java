package com.crumbs.trade.repo;

import java.util.List;

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
	
	List<Futures> findByIsNifty50True();
    List<Futures> findByIsNiftyNext50True();
    List<Futures> findByIsNifty100True();
    List<Futures> findByIsNifty200True();
    List<Futures> findByIsNifty500True();
}
