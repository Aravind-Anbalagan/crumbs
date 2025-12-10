package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.NameExpiryStrikeGroupedDto;
import com.crumbs.trade.repo.StraddleIntradayRepo;

@Service
public class StraddleGroupingService {

	@Autowired StraddleIntradayRepo straddleIntradayRepo;

    

	public List<NameExpiryStrikeGroupedDto> getGrouped() {

        List<Object[]> raw = straddleIntradayRepo.fetchNameExpiryStrikeRaw();

        Map<String, Map<String, Set<BigDecimal>>> grouped = new LinkedHashMap<>();

        for (Object[] row : raw) {

            String name = (String) row[0];
            String expiry = (String) row[1];
            BigDecimal strike = (BigDecimal) row[2];

            grouped
                .computeIfAbsent(name, n -> new LinkedHashMap<>())
                .computeIfAbsent(expiry, e -> new TreeSet<>())   // 🌟 UNIQUE + SORTED
                .add(strike);
        }

        List<NameExpiryStrikeGroupedDto> result = new ArrayList<>();

        for (var entry : grouped.entrySet()) {
            NameExpiryStrikeGroupedDto dto = new NameExpiryStrikeGroupedDto();
            dto.setName(entry.getKey());

            // convert Set<BigDecimal> → List<BigDecimal>
            Map<String, List<BigDecimal>> expToList = new LinkedHashMap<>();
            entry.getValue().forEach((exp, set) -> expToList.put(exp, new ArrayList<>(set)));

            dto.setExpiries(expToList);
            result.add(dto);
        }

        return result;
    }
}
