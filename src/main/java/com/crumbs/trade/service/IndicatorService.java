package com.crumbs.trade.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.repo.IndicatorRepo;
import org.springframework.transaction.annotation.Propagation;
@Service
public class IndicatorService {

	 @Autowired
	    private IndicatorRepo indicatorRepo;


	 @Transactional(propagation = Propagation.REQUIRES_NEW)
	 public void saveIndicator(Indicator indicator) {
	     Indicator managed = indicatorRepo.findById(indicator.getId()).orElse(indicator);
	     managed.setSuperTrendWeekly(indicator.getSuperTrendWeekly());
	     managed.setSuperTrendSignalWeekly(indicator.getSuperTrendSignalWeekly());
	     managed.setSuperTrendVolatilityWeekly(indicator.getSuperTrendVolatilityWeekly());
	     managed.setVwapWeekly(indicator.getVwapWeekly());
	     managed.setVwapSignalWeekly(indicator.getVwapSignalWeekly());
	     managed.setCpr1W(indicator.getCpr1W());
	     managed.setWeeklyRSI(indicator.getWeeklyRSI());
	     indicatorRepo.saveAndFlush(managed);
	 }
}
