package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

	// 🔥 MAIN METHOD
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

	// 🔥 CORE LOGIC
	public List<OIChartDTO> processAllStrikes(String name) {

		LocalDate today = LocalDate.now(IST);
		LocalDateTime start = today.atStartOfDay();
		LocalDateTime end = today.atTime(23, 59, 59);

		List<BigDecimal> strikes = repo.findDistinctStrikes(name);

		List<OIChartDTO> result = new ArrayList<>();
		StraddleIntraday latest = repo.findTopByNameOrderByTimestampDesc(name);

		if (latest == null || latest.getSpot() == null)
			return result;

		BigDecimal spot = latest.getSpot();

		// step logic
		int step = name.equalsIgnoreCase("CRUDEOIL") ? 100 : 50;

		BigDecimal atmStrike = calculateATM(spot, step);
		for (BigDecimal strike : strikes) {

			List<StraddleIntraday> baseRows = repo.findTop5ByNameAndStrikeAndTimestampBetweenOrderByTimestampAsc(name,
					strike, start, end);

			List<StraddleIntraday> lastRows = repo.findTop3ByNameAndStrikeAndTimestampBetweenOrderByTimestampDesc(name,
					strike, start, end);

			if (baseRows.size() < 5 || lastRows.size() < 3)
				continue;

			Collections.reverse(lastRows);

			LocalDateTime timestamp = lastRows.get(lastRows.size() - 1).getTimestamp();

			// ---------- BASE ----------
			BigDecimal baseCe = avg(baseRows, true);
			BigDecimal basePe = avg(baseRows, false);

			BigDecimal baseCeOi = avgOi(baseRows, true);
			BigDecimal basePeOi = avgOi(baseRows, false);

			// ---------- CURRENT ----------
			BigDecimal currentCe = avg(lastRows, true);
			BigDecimal currentPe = avg(lastRows, false);

			BigDecimal currentCeOi = avgOi(lastRows, true);
			BigDecimal currentPeOi = avgOi(lastRows, false);

			// ---------- CHANGE ----------
			BigDecimal ceOiChange = currentCeOi.subtract(baseCeOi);
			BigDecimal peOiChange = currentPeOi.subtract(basePeOi);

			BigDecimal ceLtpChange = currentCe.subtract(baseCe);
			BigDecimal peLtpChange = currentPe.subtract(basePe);

			BigDecimal cePct = percent(currentCe, baseCe);
			BigDecimal pePct = percent(currentPe, basePe);
			boolean isATM = strike.compareTo(atmStrike) == 0;
			result.add(new OIChartDTO(timestamp, strike,

					// ✅ OI
					currentCeOi, currentPeOi,

					// ✅ OI Change
					ceOiChange, peOiChange,

					// ✅ LTP
					currentCe, currentPe,

					// ✅ LTP Change
					ceLtpChange, peLtpChange,

					// ✅ %
					cePct, pePct, isATM));
		}

		return result;
	}

	private BigDecimal calculateATM(BigDecimal spot, int step) {

		if (spot == null)
			return BigDecimal.ZERO;

		double value = spot.doubleValue();

		double atm = Math.round(value / step) * step;

		return BigDecimal.valueOf(atm);
	}

	// ---------- HELPERS ----------
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
}