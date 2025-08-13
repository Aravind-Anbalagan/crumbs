package com.crumbs.trade.service;

import com.crumbs.trade.dto.AiRecommendation;
import com.crumbs.trade.dto.StockAnalysisDTO;
import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.repo.IndicatorRepo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;
import java.math.BigDecimal;
import java.util.*;

@Service
@Slf4j
public class AiService {

    //private static final String MODEL = "meta-llama/llama-4-scout-17b-16e-instruct";
	@Value("${spring.ai.openai.model}")
    private String model;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    @Autowired
    IndicatorRepo indicatorRepo;

    public AiService(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    public void analyzeAllStocks() {
        List<Indicator> stocks = indicatorRepo.findByDailysignalInOrPsarFlagDayInOrHeikinAshiDayInOrVolumeFlagIn(
                Arrays.asList("SUPPORT", "RESISTANCE", "SUPPORT + RESISTANCE"),
                Arrays.asList("FIRST BUY", "FIRST SELL"),
                Arrays.asList("FIRST BUY", "FIRST SELL"),
                Arrays.asList("HIGH"));

        stocks.forEach(stock -> {
            try {
                StockAnalysisDTO dto = dailyDto(stock);
                String aiResponse = analyzeDailyWithAi(dto);
                if (aiResponse == null) return;

                AiRecommendation recommendation = objectMapper.readValue(aiResponse, AiRecommendation.class);

                log.info("✅ [{}] Recommendation: {}, Confidence: {}, Target: {}, StopLoss: {}, Reason: {}",
                        dto.getTradingSymbol(),
                        recommendation.getRecommendation(),
                        recommendation.getConfidence(),
                        recommendation.getTarget(),
                        recommendation.getStopLoss(),
                        recommendation.getReason());

                stock.setDaily_aiSignal(recommendation.getRecommendation());
                stock.setDaily_aiConfidence(recommendation.getConfidence());
                stock.setDaily_aiReason(recommendation.getReason());
                indicatorRepo.save(stock);

                Thread.sleep(100); // basic throttling

            } catch (Exception e) {
                log.error("❌ [{}] AI failed: {}", stock.getId(), e.getMessage());
            }
        });
    }

    public void dailyAnalyzeStock(Indicator stock) {
        try {
            StockAnalysisDTO dto = dailyDto(stock);
            String aiResponse = analyzeDailyWithAi(dto);

            if (aiResponse == null) return;

            AiRecommendation recommendation = objectMapper.readValue(aiResponse, AiRecommendation.class);

            stock.setDaily_aiSignal(recommendation.getRecommendation());
            stock.setDaily_aiReason(recommendation.getReason());
            indicatorRepo.save(stock);

        } catch (Exception e) {
            log.error("❌ AI Error while analyzing [{}]: {}", stock.getName(), e.getMessage(), e);
        }
    }

    public void weeklyAnalyzeStock(Indicator stock) {
        try {
            StockAnalysisDTO dto = weeklyDto(stock);
            String aiResponse = analyzeWeeklyWithAi(dto);

            if (aiResponse == null) return;

            AiRecommendation recommendation = objectMapper.readValue(aiResponse, AiRecommendation.class);

            stock.setWeekly_aiSignal(recommendation.getRecommendation());
            stock.setWeekly_aiReason(recommendation.getReason());
            indicatorRepo.save(stock);

        } catch (Exception e) {
            log.error("❌ AI Error while analyzing [{}]: {}", stock.getName(), e.getMessage(), e);
        }
    }



    public void analyzeStockCombined(Indicator stock) {
        try {
            // Daily analysis
            try {
                String dailyResponse = analyzeDailyWithAi(dailyDto(stock));
                if (dailyResponse != null) {
                    AiRecommendation dailyRec = objectMapper.readValue(dailyResponse, AiRecommendation.class);
                    stock.setDaily_aiSignal(dailyRec.getRecommendation());
                    stock.setDaily_aiReason(dailyRec.getReason());
                }
            } catch (Exception e) {
                log.error("❌ Daily AI analysis error [{}]: {}", stock.getName(), e.getMessage(), e);
            }

            // Wait 3 seconds before weekly
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Weekly analysis
            try {
                String weeklyResponse = analyzeWeeklyWithAi(weeklyDto(stock));
                if (weeklyResponse != null) {
                    AiRecommendation weeklyRec = objectMapper.readValue(weeklyResponse, AiRecommendation.class);
                    stock.setWeekly_aiSignal(weeklyRec.getRecommendation());
                    stock.setWeekly_aiReason(weeklyRec.getReason());
                }
            } catch (Exception e) {
                log.error("❌ Weekly AI analysis error [{}]: {}", stock.getName(), e.getMessage(), e);
            }

            // Save the stock after both analyses
            indicatorRepo.save(stock);

        } catch (Exception e) {
            log.error("❌ AI Error while analyzing [{}]: {}", stock.getName(), e.getMessage(), e);
        }
    }


    // ---------------- DAILY ----------------

    private String analyzeDailyWithAi(StockAnalysisDTO dto) {
        String promptText = buildDailyPrompt(dto);
        return callAiAndExtractJson(dto.getTradingSymbol(), promptText);
    }

    private String buildDailyPrompt(StockAnalysisDTO dto) {
        return """
            Analyze the following stock data and return a JSON with fields:
            {
              "recommendation": "BUY/SELL/HOLD",
              "confidence": "LOW/MEDIUM/HIGH",
              "stopLoss": number,
              "target": number,
              "reason": "short explanation"
            }

            Stock Data:
            Symbol: %s
            Exchange: %s
            Current Price: %s
            RSI: %s
            Bollinger Band: %s
            CPR: %s
            Daily Signal: %s
            Hourly Signal: %s
            Heikin Ashi (Day): %s
            Pivot Levels: %s
            Time Frame: %s

            Respond ONLY in JSON format.
            """.formatted(
                dto.getTradingSymbol(),
                dto.getExchange(),
                dto.getCurrentPrice(),
                dto.getRsi(),
                dto.getBollingerBand(),
                dto.getCpr(),
                dto.getDailySignal(),
                dto.getHourlySignal(),
                dto.getHeikinAshiDay(),
                dto.getPivot(),
                dto.getTimeFrame()
        );
    }

    // ---------------- WEEKLY ----------------

    private String analyzeWeeklyWithAi(StockAnalysisDTO dto) {
        String promptText = buildWeeklyPrompt(dto);
        return callAiAndExtractJson(dto.getTradingSymbol(), promptText);
    }

    private String buildWeeklyPrompt(StockAnalysisDTO dto) {
        return """
            Analyze the following weekly stock data and return a JSON with fields:
            {
              "recommendation": "BUY/SELL/HOLD",
              "confidence": "LOW/MEDIUM/HIGH",
              "stopLoss": number,
              "target": number,
              "reason": "brief reason"
            }

            Stock Data:
            Symbol: %s
            Exchange: %s
            Current Price: %s
            RSI: %s
            Weekly Signal: %s
            Heikin Ashi (Weekly): %s
            PSAR (Weekly): %s
            Weekly Support: %s
            Weekly Resistance: %s
            Weekly Price Action Support: %s
            Weekly Price Action Resistance: %s
            Weekly Fibo Support: %s
            Weekly Fibo Resistance: %s
            Time Frame: %s

            Respond ONLY in JSON format.
            """.formatted(
                dto.getTradingSymbol(),
                dto.getExchange(),
                dto.getCurrentPrice(),
                dto.getRsi(),
                dto.getWeeklySignal(),
                dto.getHeikinAshiWeekly(),
                dto.getPsarWeekly(),
                dto.getSupport(),
                dto.getResistance(),
                dto.getWeeklyPriceActionSupport(),
                dto.getWeeklyPriceActionResistance(),
                dto.getWeeklyFiboSupport(),
                dto.getWeeklyFiboResistance(),
                dto.getTimeFrame()
        );
    }

    // ---------------- UTILITIES ----------------

    private String callAiAndExtractJson(String symbol, String promptText) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(model)
                .build();
        Prompt prompt = new Prompt(promptText, options);

        String aiResponse = chatClient.call(prompt).getResult().getOutput().getContent();
        //log.info("🧠 [{}] Raw AI Response: {}", symbol, aiResponse);

        String json = extractJson(aiResponse);
        if (json == null) {
            log.warn("⚠️ [{}] AI returned no valid JSON", symbol);
            return null;
        }
        return json;
    }

    private String extractJson(String response) {
        if (response == null) return null;
        int start = response.indexOf("{");
        int end = response.lastIndexOf("}");
        if (start != -1 && end != -1 && start < end) {
            return response.substring(start, end + 1).trim();
        }
        return null;
    }

    private StockAnalysisDTO dailyDto(Indicator entity) {
        return StockAnalysisDTO.builder()
                .tradingSymbol(entity.getTradingSymbol())
                .exchange(entity.getExchange())
                .currentPrice(entity.getCurrentPrice())
                .rsi(entity.getDailyRSI())
                .bollingerBand(parseList(entity.getBollingerband()))
                .dailySignal(entity.getDailysignal())
                .hourlySignal(entity.getHourlySignal())
                .heikinAshiDay(entity.getHeikinAshiDay())
                .psarDay(entity.getPsarFlagDay())
                .pivot(parseMap(entity.getPivot()))
                .timeFrame(entity.getTimeFrame())
                .support(entity.getDailysupport())
                .resistance(entity.getDailyresistance())
                .dailyPriceActionSupport(entity.getDailyPriceActionSupport())
                .dailyPriceActionResistance(entity.getDailyPriceActionResistance())
                .dailyFiboSupport(entity.getDaily_fiboSupport())
                .dailyFiboResistance(entity.getDaily_fiboResistance())
                .build();
    }

    private StockAnalysisDTO weeklyDto(Indicator entity) {
        return StockAnalysisDTO.builder()
                .tradingSymbol(entity.getTradingSymbol())
                .exchange(entity.getExchange())
                .currentPrice(entity.getCurrentPrice())
                .rsi(entity.getWeeklyRSI())
                .weeklySignal(entity.getWeeklysignal())
                .heikinAshiWeekly(entity.getHeikinAshiWeekly())
                .psarWeekly(entity.getPsarFlagWeekly())
                .timeFrame(entity.getTimeFrame())
                .support(entity.getWeeklysupport())
                .resistance(entity.getWeeklyresistance())
                .weeklyPriceActionSupport(entity.getWeeklyPriceActionSupport())
                .weeklyPriceActionResistance(entity.getWeeklyPriceActionResistance())
                .weeklyFiboSupport(entity.getWeekly_fiboSupport())
                .weeklyFiboResistance(entity.getWeekly_fiboResistance())
                .build();
    }

    private List<BigDecimal> parseList(String jsonArray) {
        try {
            if (jsonArray == null) return Collections.emptyList();
            return objectMapper.readValue(jsonArray, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("⚠️ Failed to parse list: {}", jsonArray);
            return Collections.emptyList();
        }
    }

    private Map<String, BigDecimal> parseMap(String json) {
        try {
            if (json == null) return Collections.emptyMap();
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("⚠️ Failed to parse map: {}", json);
            return Collections.emptyMap();
        }
    }

    public String ask(String userMessage) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(model)
                .build();
        Prompt prompt = new Prompt(userMessage, options);
        return chatClient.call(prompt).getResult().getOutput().getContent();
    }
}

