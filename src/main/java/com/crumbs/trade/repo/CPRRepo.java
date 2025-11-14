package com.crumbs.trade.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.CPR;

@Repository
public interface CPRRepo extends JpaRepository<CPR, Long> {

	CPR findByName(String name);

}
