package com.crumbs.trade.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.Indicator;

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
	
	List<Indicator> findByIntradayIsNotNull();

	List<Indicator> findByMailsentIsNotNullOrderByMailsentAsc();

	List<Indicator> findByIntradayIsNotNullOrderByIntradayAsc();

	List<Indicator> findByHeikinAshiDayAndPsarFlagDay(String heikinAshiDay, String psarFlagDay);

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


}