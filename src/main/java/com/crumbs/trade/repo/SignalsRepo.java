package com.crumbs.trade.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.Signals;

@Service
public interface SignalsRepo extends JpaRepository<Signals,Long> {

}
