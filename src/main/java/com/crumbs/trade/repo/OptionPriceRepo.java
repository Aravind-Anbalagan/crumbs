package com.crumbs.trade.repo;

import com.crumbs.trade.entity.OptionPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface OptionPriceRepo extends JpaRepository<OptionPrice, Long> {

    // 👈 Add this line to fix the error
    Optional<OptionPrice> findByTokenAndEvaluatedDateAndTimeFrame(String token, LocalDate evaluatedDate, String timeFrame);
    List<OptionPrice> findAllByTimeFrameOrderByEvaluatedAtDesc(String timeFrame);
    // This fetches data ordered by the most recent evaluations for your API
    List<OptionPrice> findAllByOrderByEvaluatedAtDesc();

    @Modifying
    @Query("delete from OptionPrice p" )
    void deleteAll();
}