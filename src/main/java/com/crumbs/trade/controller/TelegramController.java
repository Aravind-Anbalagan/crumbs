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

    // Single message (optional)
    @PostMapping("/send")
    public String send(@RequestBody Map<String, String> payload) {
        String text = payload.getOrDefault("text", "Hello from Spring Boot!");
        boolean ok = telegramService.sendMessage(text);
        return ok ? "✅ Sent!" : "❌ Failed!";
    }

    // 🔥 Broadcast message
    @PostMapping("/broadcast")
    public String broadcast(@RequestBody Map<String, String> payload) {
        String text = payload.get("text");

        if (text == null || text.isBlank()) {
            return "❌ text is required";
        }

        int count = telegramService.sendBroadcast(text);
        return "✅ Broadcast sent to " + count + " users";
    }

    // ✅ Telegram webhook (called ONLY by Telegram)
    @PostMapping("/webhook")
    public void onUpdate(@RequestBody Map<String, Object> update) {

        Map<String, Object> message = (Map<String, Object>) update.get("message");
        if (message == null) return;

        Map<String, Object> chat = (Map<String, Object>) message.get("chat");
        Map<String, Object> from = (Map<String, Object>) message.get("from");

        Long chatId = ((Number) chat.get("id")).longValue();
        String username = from != null ? (String) from.get("username") : null;
        String text = (String) message.get("text");

        if ("/start".equals(text)) {
            telegramService.saveUser(chatId, username);
            telegramService.sendToChat(
                chatId,
                "✅ You are subscribed for alerts"
            );
        }
    }
}

