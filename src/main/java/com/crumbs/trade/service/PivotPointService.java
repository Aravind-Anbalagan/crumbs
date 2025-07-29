package com.crumbs.trade.service;


import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.PivotRequest;
import com.crumbs.trade.dto.PivotResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PivotPointService {

    public PivotResponse calculatePivot(PivotRequest request) {
        BigDecimal high = request.getHigh();
        BigDecimal low = request.getLow();
        BigDecimal close = request.getClose();
        String method = request.getMethod().toLowerCase();

        PivotResponse response = switch (method) {
            case "fibonacci" -> calculateFibonacci(high, low, close);
            case "camarilla" -> calculateCamarilla(high, low, close);
            default -> calculateStandard(high, low, close);
        };

        calculateCpr(high, low, close, response);
        return response;
    }

    private PivotResponse calculateStandard(BigDecimal high, BigDecimal low, BigDecimal close) {
        BigDecimal pivot = high.add(low).add(close).divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
        BigDecimal range = high.subtract(low);

        return new PivotResponse(
                pivot,
                pivot.multiply(BigDecimal.valueOf(2)).subtract(low),
                pivot.add(range),
                high.add(BigDecimal.valueOf(2).multiply(pivot.subtract(low))),
                pivot.multiply(BigDecimal.valueOf(2)).subtract(high),
                pivot.subtract(range),
                low.subtract(BigDecimal.valueOf(2).multiply(high.subtract(pivot)))
        );
    }

    private PivotResponse calculateFibonacci(BigDecimal high, BigDecimal low, BigDecimal close) {
        BigDecimal pivot = high.add(low).add(close).divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
        BigDecimal range = high.subtract(low);

        return new PivotResponse(
                pivot,
                pivot.add(range.multiply(new BigDecimal("0.382"))),
                pivot.add(range.multiply(new BigDecimal("0.618"))),
                pivot.add(range.multiply(new BigDecimal("1.000"))),
                pivot.subtract(range.multiply(new BigDecimal("0.382"))),
                pivot.subtract(range.multiply(new BigDecimal("0.618"))),
                pivot.subtract(range.multiply(new BigDecimal("1.000")))
        );
    }

    private PivotResponse calculateCamarilla(BigDecimal high, BigDecimal low, BigDecimal close) {
        BigDecimal range = high.subtract(low);
        BigDecimal multiplier = new BigDecimal("1.1").divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP);

        return new PivotResponse(
                close,
                close.add(range.multiply(multiplier)),
                close.add(range.multiply(multiplier).multiply(BigDecimal.valueOf(2))),
                close.add(range.multiply(multiplier).multiply(BigDecimal.valueOf(3))),
                close.subtract(range.multiply(multiplier)),
                close.subtract(range.multiply(multiplier).multiply(BigDecimal.valueOf(2))),
                close.subtract(range.multiply(multiplier).multiply(BigDecimal.valueOf(3)))
        );
    }

    private void calculateCpr(BigDecimal high, BigDecimal low, BigDecimal close, PivotResponse response) {
        BigDecimal pivot = high.add(low).add(close).divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
        BigDecimal bc = high.add(low).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);

        if (pivot.compareTo(bc) > 0) {
            response.setTc(pivot);
            response.setBc(bc);
        } else {
            response.setTc(bc);
            response.setBc(pivot);
        }

        response.setPivot(pivot); // Ensure pivot is CPR central as well
    }
}

