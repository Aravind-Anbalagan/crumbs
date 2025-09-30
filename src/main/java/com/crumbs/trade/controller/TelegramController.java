package com.crumbs.trade.controller;



import org.springframework.web.bind.annotation.*;

import com.crumbs.trade.service.TelegramService;

import java.util.Map;

@RestController
@RequestMapping("/api/telegram")
public class TelegramController {

    private final TelegramService telegramService;

    public TelegramController(TelegramService telegramService) {
        this.telegramService = telegramService;
    }

    @PostMapping("/send")
    public String send(@RequestBody Map<String, String> payload) {
        String text = payload.getOrDefault("text", "Hello from Spring Boot!");
        boolean ok = telegramService.sendMessage(text);
        return ok ? "✅ Sent!" : "❌ Failed!";
    }
}
