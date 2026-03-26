package com.crumbs.trade.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.crumbs.trade.entity.OIResult;
import com.crumbs.trade.repo.OIResultRepo;

@RestController
@RequestMapping("/api/oi")
public class OIController {

	@Autowired
	private OIResultRepo repo;

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	// 🔥 1. LATEST SNAPSHOT (for bar chart)
	@GetMapping("/latest/{name}")
	public List<OIResult> getLatest(@PathVariable String name) {
		return repo.findLatestByName(name);
	}

	// 🔥 2. FULL DAY DATA (for replay / analysis)
	@GetMapping("/day/{name}")
	public List<OIResult> getToday(@PathVariable String name) {

		LocalDate today = LocalDate.now(IST);

		return repo.findByNameAndTimestampBetweenOrderByTimestampAsc(name, today.atStartOfDay(),
				today.atTime(23, 59, 59));
	}

	// 🔥 3. SPECIFIC TIME SNAPSHOT (optional, powerful)
	@GetMapping("/time/{name}")
	public List<OIResult> getByTime(@PathVariable String name, @RequestParam String time // format: 2026-03-26T10:15:00
	) {
		LocalDateTime timestamp = LocalDateTime.parse(time);

		return repo.findByNameAndTimestamp(name, timestamp);
	}
}