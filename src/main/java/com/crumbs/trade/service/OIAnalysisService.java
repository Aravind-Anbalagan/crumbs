package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crumbs.trade.dto.OIChartDTO;
import com.crumbs.trade.entity.OIResult;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.repo.OIResultRepo;
import com.crumbs.trade.repo.StraddleIntradayRepo;

@Service
public class OIAnalysisService {

	@Autowired
	private StraddleIntradayRepo repo;

	@Autowired
	private OIResultRepo oiResultRepo;

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	// 🔥 MAIN METHOD (Preserved your exact batch-saving logic)
	@Transactional
	public void saveSnapshot(String name) {

		List<OIChartDTO> list = processAllStrikes(name);

		if (list == null || list.isEmpty())
			return;

		List<OIResult> entities = new ArrayList<>();

		for (OIChartDTO dto : list) {
			OIResult e = new OIResult();
			e.setName(name);
			e.setStrike(dto.strike());

			// ✅ OI (height)
			e.setCeOi(dto.ceOi());
			e.setPeOi(dto.peOi());

			// ✅ OI Change (intensity)
			e.setCeOiChange(dto.ceOiChange());
			e.setPeOiChange(dto.peOiChange());

			// ✅ LTP (price)
			e.setCeLtp(dto.ceLtp());
			e.setPeLtp(dto.peLtp());

			// ✅ LTP Change (momentum)
			e.setCeLtpChange(dto.ceLtpChange());
			e.setPeLtpChange(dto.peLtpChange());

			// ✅ % change
			e.setCePct(dto.cePct());
			e.setPePct(dto.pePct());

			e.setTimestamp(dto.timestamp());
			e.setIsATM(dto.isATM());
			entities.add(e);
		}

		oiResultRepo.saveAll(entities); // 🚀 batch insert
	}

	// 🔥 CORE LOGIC (Optimized performance: 1 DB query instead of 100+)
	public List<OIChartDTO> processAllStrikes(String name) {

		LocalDate today = LocalDate.now(IST);
		LocalDateTime start = today.atStartOfDay();
		LocalDateTime end = today.atTime(23, 59, 59);

		// 1. Fetch all records for the instrument today in one go
		List<StraddleIntraday> allRows = repo.findByNameAndTimestampBetweenOrderByTimestampAsc(name, start, end);

		if (allRows.isEmpty())
			return Collections.emptyList();

		// 2. Identify Latest Spot and ATM Step Logic
		StraddleIntraday latest = allRows.get(allRows.size() - 1);
		BigDecimal spot = latest.getSpot();
		
		// Fixed: Now correctly identifies CRUDEOILM
		int step = getStrikeStep(name);
		BigDecimal atmStrike = calculateATM(spot, step);

		// 3. Group by Strike price in-memory to preserve logic for every strike
		Map<BigDecimal, List<StraddleIntraday>> groupedByStrike = allRows.stream()
				.collect(Collectors.groupingBy(StraddleIntraday::getStrike));

		List<OIChartDTO> result = new ArrayList<>();

		groupedByStrike.forEach((strike, rows) -> {
			
			// Ensuring we have enough data (5 for base + 3 for current)
			if (rows.size() < 8) return; 

			// ---------- BASE (First 5 records of the day) ----------
			List<StraddleIntraday> baseRows = rows.subList(0, 5);
			
			// ---------- CURRENT (Last 3 records of the day) ----------
			List<StraddleIntraday> lastRows = rows.subList(rows.size() - 3, rows.size());

			LocalDateTime timestamp = lastRows.get(lastRows.size() - 1).getTimestamp();

			// Calculations (Averages)
			BigDecimal baseCe = avg(baseRows, true);
			BigDecimal basePe = avg(baseRows, false);
			BigDecimal baseCeOi = avgOi(baseRows, true);
			BigDecimal basePeOi = avgOi(baseRows, false);

			BigDecimal currentCe = avg(lastRows, true);
			BigDecimal currentPe = avg(lastRows, false);
			BigDecimal currentCeOi = avgOi(lastRows, true);
			BigDecimal currentPeOi = avgOi(lastRows, false);

			// Calculations (Changes)
			BigDecimal ceOiChange = currentCeOi.subtract(baseCeOi);
			BigDecimal peOiChange = currentPeOi.subtract(basePeOi);
			BigDecimal ceLtpChange = currentCe.subtract(baseCe);
			BigDecimal peLtpChange = currentPe.subtract(basePe);

			BigDecimal cePct = percent(currentCe, baseCe);
			BigDecimal pePct = percent(currentPe, basePe);
			
			boolean isATM = strike.compareTo(atmStrike) == 0;

			result.add(new OIChartDTO(timestamp, strike,
					currentCeOi, currentPeOi,
					ceOiChange, peOiChange,
					currentCe, currentPe,
					ceLtpChange, peLtpChange,
					cePct, pePct, isATM));
		});

		return result;
	}

	// Logic Fix: Handles CRUDEOILM and NIFTY variants
	private int getStrikeStep(String name) {
		String upperName = name.toUpperCase();
		if (upperName.contains("BANKNIFTY")) return 100;
		if (upperName.contains("NIFTY")) return 50;
		if (upperName.contains("CRUDEOIL")) return 100; 
		return 50;
	}

	private BigDecimal calculateATM(BigDecimal spot, int step) {
		if (spot == null || step <= 0)
			return BigDecimal.ZERO;
		double value = spot.doubleValue();
		double atm = Math.round(value / step) * step;
		return BigDecimal.valueOf(atm);
	}

	// ---------- HELPERS (Preserved) ----------
	private BigDecimal avg(List<StraddleIntraday> rows, boolean isCe) {
		return rows.stream().map(r -> isCe ? r.getCePrice() : r.getPePrice()).reduce(BigDecimal.ZERO, BigDecimal::add)
				.divide(BigDecimal.valueOf(rows.size()), 4, RoundingMode.HALF_UP);
	}

	private BigDecimal avgOi(List<StraddleIntraday> rows, boolean isCe) {
		return rows.stream().map(r -> isCe ? r.getCeOi() : r.getPeOi()).reduce(BigDecimal.ZERO, BigDecimal::add)
				.divide(BigDecimal.valueOf(rows.size()), 4, RoundingMode.HALF_UP);
	}

	private BigDecimal percent(BigDecimal current, BigDecimal base) {
		if (current == null || base == null || base.compareTo(BigDecimal.ZERO) == 0) {
			return BigDecimal.ZERO;
		}
		return current.subtract(base).divide(base, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
	}

	// Preserved from your original code
	public List<BigDecimal> getStrikeList(String name) {
		if (name == null || name.isEmpty()) {
			return Collections.emptyList();
		}
		List<BigDecimal> strikes = repo.findDistinctStrikes(name);
		strikes.sort(BigDecimal::compareTo);
		return strikes;
	}
}