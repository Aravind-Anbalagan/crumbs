package com.crumbs.trade.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.Brokers;

@Repository
public interface BrokersRepo extends JpaRepository<Brokers, Long> {
	  Optional<Brokers> findByBrokername(String brokername);
}
