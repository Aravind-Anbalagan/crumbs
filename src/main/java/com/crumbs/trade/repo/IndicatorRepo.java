package com.crumbs.trade.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.Indicator;

import jakarta.transaction.Transactional;

@Repository
public interface IndicatorRepo extends JpaRepository<Indicator, Long> {

	@Modifying
	@Query("delete from Indicator I")
	void deleteAll();

	List<Indicator> findByDailysignalInOrWeeklysignalInOrFourHoursignalInOrMonthlysignalInAndPsarFlagDayAndHeikinAshiDay(
			List<String> signal, List<String> weeklysignal, List<String> fourhoursignal, List<String> monthlysignal,
			String psar, String heikinAshi);

	List<Indicator> findByDailysignalInAndPsarFlagDayInAndHeikinAshiDayIn(List<String> signal, List<String> psar,
			List<String> heikinAshi);

	List<Indicator> findByDailysignalInOrPsarFlagDayInOrHeikinAshiDayInOrVolumeFlagIn(List<String> signal,
			List<String> psar, List<String> heikinAshi, List<String> volume);

	Indicator findByname(String name);

	Optional<Indicator> findByNameIgnoreCase(String name);

	List<Indicator> findBymailsentIsNotNull();
	
	List<Indicator> findByIntradayIsNotNullAndTradetypeIn(List<String> tradetypes);

	List<Indicator> findByMailsentIsNotNullOrderByMailsentAsc();

	List<Indicator> findByIntradayIsNotNullOrderByIntradayAsc();

	List<Indicator> findByHeikinAshiDayAndPsarFlagDay(String heikinAshiDay, String psarFlagDay);
	List<Indicator>findByHeikinAshiHourlyAndPsarFlagHourly(String heikinAshiHourly, String psarFlagHourly);

	@Query("SELECT i FROM Indicator i WHERE (:heikin IS NULL OR i.heikinAshiDay = :heikin OR i.heikinAshiWeekly = :heikin OR i.heikinAshiHourly = :heikin) AND (:psar IS NULL OR i.psarFlagDay = :psar OR i.psarFlagWeekly = :psar OR i.psarFlagHourly = :psar)")
	List<Indicator> findByHeikinAndPsar(String heikin, String psar);

	@Query("""
		    SELECT i
		    FROM Indicator i
		    WHERE (
		        /* COMBINED MODE */
		        :isCombined = true AND (
		            (
		                (:heikin IS NULL OR i.heikinAshiDay = :heikin)
		                AND (:psar IS NULL OR i.psarFlagDay = :psar)
		            )
		            OR
		            (
		                (:heikin IS NULL OR i.heikinAshiWeekly = :heikin)
		                AND (:psar IS NULL OR i.psarFlagWeekly = :psar)
		            )
		        )
		    )
		    OR (
		        /* NON-COMBINED MODE */
		        :isCombined = false AND (
		            (:heikin IS NULL
		                OR (:isWeekly = false AND i.heikinAshiDay = :heikin)
		                OR (:isWeekly = true AND i.heikinAshiWeekly = :heikin)
		            )
		            AND
		            (:psar IS NULL
		                OR (:isWeekly = false AND i.psarFlagDay = :psar)
		                OR (:isWeekly = true AND i.psarFlagWeekly = :psar)
		            )
		            AND
		            (
		                /* DAILY MODE: Keep old logic */
		                :isWeekly = false
		                /* WEEKLY MODE:
		                   If filters are NULL => allow all (skip FIRST BUY/SELL restriction) */
		                OR (:isWeekly = true AND (:heikin IS NULL AND :psar IS NULL))
		                /* If filters are set => apply FIRST BUY/SELL restriction */
		                OR (:isWeekly = true AND (
		                    i.psarFlagWeekly IN ('FIRST BUY', 'FIRST SELL')
		                    OR i.heikinAshiWeekly IN ('FIRST BUY', 'FIRST SELL')
		                ))
		            )
		        )
		    )
		""")
		List<Indicator> findIndicatorsWithFilters(
		    @Param("heikin") String heikin,
		    @Param("psar") String psar,
		    @Param("isWeekly") boolean isWeekly,
		    @Param("isCombined") boolean isCombined
		);



	// List<Indicator>
	// findByOpenflagInAndLast3daycandleflagInAndCprflagIn(List<String>
	// openFlag,List<String> last3candle,List<String> cpr);

	List<Indicator> findByPsarFlagDayInAndHeikinAshiDayIn(List<String> psar, List<String> heikinAshi);

	List<Indicator> findByOnedayIsNotNullAndOneweekIsNotNull();

	List<Indicator> findByPsarFlagDayAndHeikinAshiDay(String psar, String heikin);
	
	@Query("SELECT i FROM Indicator i")
    List<Indicator> findAllData();

    @Query("SELECT i FROM Indicator i WHERE i.heikinAshiDay = :value AND i.psarFlagDay = :value")
    List<Indicator> findDayByValue(@Param("value") String value);

    @Query("SELECT i FROM Indicator i WHERE i.heikinAshiWeekly = :value AND i.psarFlagWeekly = :value")
    List<Indicator> findWeeklyByValue(@Param("value") String value);
    
    List<Indicator> findByHeikinAshiWeeklyAndPsarFlagWeekly(String heikinAshiWeekly, String psarFlagWeekly);

    List<Indicator> findByIntraday(String value);
    
    List<Indicator> findByLast3daycandleflag(String up);

	List<Indicator> findByHeikinAshiDayInAndPsarFlagDayInAndOptions(List<String> signals, List<String> signals2,
			String option);
	
	List<Indicator> findByTradetypeAndOptions(String type,String option);
	
	// IndicatorRepo.java
	@Modifying
	@Transactional
	@Query("UPDATE Indicator i SET " +
	       "i.currentPrice = :currentPrice, " +
	       "i.modifiedDate = :modifiedDate, " +
	       "i.first3FiveMinsCandle = :first3FiveMinsCandle, " +
	       "i.prevdayclosepriceflag = :prevdayclosepriceflag, " +
	       "i.last3daycandleflag = :last3daycandleflag, " +
	       "i.cprflag = :cprflag, " +
	       "i.pivotFlag = :pivotFlag, " +
	       "i.openPrice = :openPrice " +
	       "WHERE i.id = :id")
	void updateStockProcessingFields(
	    @Param("id") Long id,
	    @Param("currentPrice") BigDecimal currentPrice,
	    @Param("modifiedDate") LocalDateTime modifiedDate,
	    @Param("first3FiveMinsCandle") String first3FiveMinsCandle,
	    @Param("prevdayclosepriceflag") String prevdayclosepriceflag,
	    @Param("last3daycandleflag") String last3daycandleflag,
	    @Param("cprflag") String cprflag,
	    @Param("pivotFlag") String pivotFlag,
	    @Param("openPrice") BigDecimal openPrice
	);
	
	@Modifying
	@Transactional
	@Query("UPDATE Indicator i SET i.intraday = :intraday, i.tradetype = :tradetype, i.executedDate = :executedDate WHERE i.id = :id")
	void updateIntradayFields(@Param("id") Long id, @Param("intraday") String intraday, 
	                          @Param("tradetype") String tradetype, @Param("executedDate") LocalDateTime executedDate);

	// IndicatorRepo
	@Modifying
	@Transactional
	@Query("UPDATE Indicator i SET i.result = :result WHERE i.id = :id")
	void updateResult(@Param("id") Long id, @Param("result") String result);
	
	// All stocks in full bull stack
	List<Indicator> findByMaHierarchyFlag(String maHierarchyFlag);

	// BUY stocks that are also F&O eligible
	List<Indicator> findByMaHierarchyFlagAndOptions(String maHierarchyFlag, String options);

	// BUY/SELL stocks grouped by sector
	List<Indicator> findByMaHierarchyFlagAndSector(String maHierarchyFlag, String sector);

	// Combined with existing Heikin-Ashi for stronger confluence
	List<Indicator> findByMaHierarchyFlagAndHeikinAshiDay(String maHierarchyFlag, String heikinAshiDay);

	// Combined with PSAR for triple confirmation
	List<Indicator> findByMaHierarchyFlagAndHeikinAshiDayAndPsarFlagDay(
	    String maHierarchyFlag, String heikinAshiDay, String psarFlagDay);
}