package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.Expiry;



@Repository
public interface ExpiryRepo extends JpaRepository<Expiry, Long> {

	List<Expiry> findByActive(String active);
}
