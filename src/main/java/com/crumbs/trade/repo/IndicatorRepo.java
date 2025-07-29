package com.crumbs.trade.repo;


import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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
	List<Indicator> findByDailysignalInAndPsarFlagDayInAndHeikinAshiDayIn(
			List<String> signal,
			List<String> psar, List<String> heikinAshi);
	List<Indicator> findByDailysignalInOrPsarFlagDayInOrHeikinAshiDayIn(
			List<String> signal,
			List<String> psar, List<String> heikinAshi);
	Indicator findByname(String name);
	Optional<Indicator> findByNameIgnoreCase(String name);

	List<Indicator> findBymailsentIsNotNull();
	List<Indicator> findByMailsentIsNotNullOrderByMailsentAsc();
	List<Indicator> findByIntradayIsNotNullOrderByIntradayAsc();
	List<Indicator> findByHeikinAshiDayAndPsarFlagDay(String heikinAshiDay, String psarFlagDay);

	@Query("SELECT i FROM Indicator i WHERE (:heikin IS NULL OR i.heikinAshiDay = :heikin OR i.heikinAshiWeekly = :heikin OR i.heikinAshiHourly = :heikin) AND (:psar IS NULL OR i.psarFlagDay = :psar OR i.psarFlagWeekly = :psar OR i.psarFlagHourly = :psar)")
	    List<Indicator> findByHeikinAndPsar(String heikin, String psar);
	// List<Indicator>
	// findByOpenflagInAndLast3daycandleflagInAndCprflagIn(List<String>
	// openFlag,List<String> last3candle,List<String> cpr);
}