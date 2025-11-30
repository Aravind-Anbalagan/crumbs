package com.crumbs.trade.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.crumbs.trade.entity.Prediction;

public interface PredictionRepo extends JpaRepository<Prediction, Long> {
}
