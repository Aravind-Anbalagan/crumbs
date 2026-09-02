package com.crumbs.trade.repo;

import com.crumbs.trade.entity.OptionPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface OptionPriceRepo extends JpaRepository<OptionPrice, Long> {

    // 👈 Add this line to fix the error
    Optional<OptionPrice> findByTokenAndEvaluatedDate(String token, LocalDate evaluatedDate);

    // This fetches data ordered by the most recent evaluations for your API
    List<OptionPrice> findAllByOrderByEvaluatedAtDesc();
}