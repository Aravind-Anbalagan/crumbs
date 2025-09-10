package com.crumbs.trade.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.Chart;

@Repository
public interface ChartRepo extends JpaRepository<Chart, Long> {

}
