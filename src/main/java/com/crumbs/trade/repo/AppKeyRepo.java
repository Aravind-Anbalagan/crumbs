package com.crumbs.trade.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.AppKey;
import com.crumbs.trade.entity.Candle;

@Repository
public interface AppKeyRepo extends JpaRepository<AppKey, Long> {

}
