package com.crumbs.trade.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.crumbs.trade.service.AiService;

import jakarta.annotation.PostConstruct;



@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;

    @Value("${spring.ai.openai.model}")
    private String model;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostConstruct
    public void printModel() {
        System.out.println("🔍 Using model from config: " + model);
    }


    // 🔁 Analyze top 1000 stocks
    @PostMapping("/analyze")
    public ResponseEntity<String> analyzeStocks() {
        aiService.analyzeAllStocks();
        return ResponseEntity.ok("AI analysis Completed");
    }

    // 🔍 Analyze a single stock by name
    @PostMapping("/analyze/name/{name}")
    public ResponseEntity<String> analyzeStockByName(@PathVariable String name) {
        //aiService.dailyAnalyzeStockByName(name);
        return ResponseEntity.ok("Completed");
    }

    // Optional: test AI with a custom single message
    @PostMapping("/test")
    public ResponseEntity<String> testAi(@RequestBody String message) {
        String reply = aiService.ask(message);
        return ResponseEntity.ok(reply);
    }
}