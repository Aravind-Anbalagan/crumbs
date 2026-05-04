package com.crumbs.trade.service;

import com.crumbs.trade.dto.MultiLegGreeksChartPoint;
import com.crumbs.trade.repo.OptionsGreeksRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GreekDetailsService {

    private final OptionsGreeksRepo optionsGreeksRepo;

    public List<MultiLegGreeksChartPoint> getMultiLegChartData(String symbol, BigDecimal ceStrike, BigDecimal peStrike) {
    	BigDecimal ceS = ceStrike.setScale(4, RoundingMode.HALF_UP);
        BigDecimal peS = peStrike.setScale(4, RoundingMode.HALF_UP);
        
        List<Object[]> rawData = optionsGreeksRepo.findHistoricalMultiLegWithGreeks(symbol, ceS, peS);

     // Inside GreekDetailsService.java -> getMultiLegChartData()

     // Define this at the class level for efficiency
        final DateTimeFormatter IST_FORMATTER = 
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss");

        // Inside your stream/map logic:
     // Inside GreekDetailsService.java -> getMultiLegChartData()

     // Inside GreekDetailsService.java -> getMultiLegChartData()

        return rawData.stream().map(row -> {
            // 1. Timestamp handling (Same as before)
            Object timestampObj = row[0];
            LocalDateTime ldt = (timestampObj instanceof java.sql.Timestamp) 
                    ? ((java.sql.Timestamp) timestampObj).toLocalDateTime() 
                    : (LocalDateTime) timestampObj;
                    
            long unixTimeSeconds = ldt.atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond();
            String istTime = ldt.format(IST_FORMATTER);
            
            // 2. Return the record with all 15 arguments
            return new MultiLegGreeksChartPoint(
                unixTimeSeconds,
                istTime,
                safeBigDecimal(row[1]), safeBigDecimal(row[2]), safeBigDecimal(row[3]), // ceLtp, peLtp, combined
                safeDouble(row[4]), safeDouble(row[5]), safeDouble(row[6]), safeDouble(row[7]), safeDouble(row[8]), // CE Greeks
                safeDouble(row[9]), safeDouble(row[10]), safeDouble(row[11]), safeDouble(row[12]), safeDouble(row[13]) // PE Greeks
            );
        }).collect(Collectors.toList());
    }

    private double safeDouble(Object value) {
        return value == null ? 0.0 : ((Number) value).doubleValue();
    }

    private BigDecimal safeBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        return new BigDecimal(value.toString());
    }
}