package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.OHLC;
import com.crumbs.trade.dto.VolumeByDate;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.CandleRepo;
import com.crumbs.trade.repo.IndexesRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.transaction.Transactional;



@Service
public class VolumeService {
	
	Logger logger = LoggerFactory.getLogger(VolumeService.class);
	
	private static final ObjectMapper objectMapper = new ObjectMapper();
	
	@Autowired
	IndexesRepo indexesRepo;
	
	
	
	@Autowired
	CandleRepo candleRepo;
	
	public List<LocalDate> getLastWorkingDays(int days) {
		List<LocalDate> workingDays = new ArrayList<>();
        LocalDate currentDay = LocalDate.now();

        while (workingDays.size() < days) {
            if (currentDay.getDayOfWeek() != DayOfWeek.SATURDAY &&
                currentDay.getDayOfWeek() != DayOfWeek.SUNDAY) {
                workingDays.add(currentDay);
            }

            currentDay = currentDay.minusDays(1); // Move to the previous day
        }

        return workingDays;
    }
	
	public static BigDecimal calculateAverage(List<BigDecimal> avgVolume) {
        if (avgVolume == null || avgVolume.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : avgVolume) {
            sum = sum.add(value);
        }

        return sum.divide(
            BigDecimal.valueOf(avgVolume.size()), // Convert size to BigDecimal
            2, // Keep 2 decimal places
            RoundingMode.HALF_UP // Use standard rounding
        );
    }
	
	public String calVolumeAvg(List<PricesIndex> volumeList) {
		List<BigDecimal> avgVolume = IntStream.range(1, volumeList.size()) // Start from index 1
				.mapToObj(i -> volumeList.get(i).getVolume()).collect(Collectors.toList());

		BigDecimal average = calculateAverage(avgVolume);

		if (volumeList.get(0).getVolume().compareTo(average) > 0) {
			// Found High Volume
			return "HIGH";
		} else
			return null;

	}
	
	 public static String getLastNDaysVolumeJsonString(List<PricesIndex> pricesList, int n) {
	        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	        // Group by date
	        Map<LocalDate, List<PricesIndex>> groupedByDate = pricesList.stream()
	            .collect(Collectors.groupingBy(
	                pi -> LocalDate.parse(pi.getTimestamp().substring(0, 10), formatter)
	            ));

	        // Convert to list of DTO
	        List<VolumeByDate> volumeList = groupedByDate.keySet().stream()
	            .sorted(Comparator.reverseOrder())
	            .limit(n)
	            .map(date -> {
	                BigDecimal totalVolume = groupedByDate.get(date).stream()
	                    .map(PricesIndex::getVolume)
	                    .filter(Objects::nonNull)
	                    .reduce(BigDecimal.ZERO, BigDecimal::add);
	                return new VolumeByDate(date.toString(), totalVolume);
	            })
	            .collect(Collectors.toList());

	        // Convert to JSON string
	        try {
	            return objectMapper.writeValueAsString(volumeList);
	        } catch (JsonProcessingException e) {
	            e.printStackTrace();
	            return "[]"; // fallback empty JSON array
	        }
	    }
	
}
