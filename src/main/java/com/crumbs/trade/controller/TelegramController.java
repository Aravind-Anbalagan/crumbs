package com.crumbs.trade.controller;

import com.crumbs.trade.service.TelegramService;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/telegram")
public class TelegramController {
	private static final Logger logger = LogManager.getLogger(TelegramController.class);
    private final TelegramService telegramService;

    public TelegramController(TelegramService telegramService) {
        this.telegramService = telegramService;
    }

    // ------------------------
    // Send single message
    // ------------------------
    @PostMapping("/send")
    public ResponseEntity<String> send(@RequestBody Map<String, String> payload) {

        String text = payload.get("text");

        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body("❌ text is required");
        }

        boolean ok = telegramService.sendMessage(text);
        return ResponseEntity.ok(ok ? "✅ Sent!" : "❌ Failed!");
    }

    // ------------------------
    // Broadcast message
    // ------------------------
    @PostMapping("/broadcast")
    public ResponseEntity<String> broadcast(@RequestBody Map<String, String> payload) {

        String text = payload.get("text");

        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body("❌ text is required");
        }

        int count = telegramService.sendBroadcast(text);
        return ResponseEntity.ok("✅ Broadcast sent to " + count + " users");
    }

    // ------------------------
    // Telegram Webhook
    // ------------------------
    @PostMapping("/webhook")
    public ResponseEntity<Void> onUpdate(@RequestBody Map<String, Object> update) {
    	logger.info("Webhook Invoked");
        try {
            // Telegram sends "message" object
            Object messageObj = update.get("message");
            if (!(messageObj instanceof Map)) {
                return ResponseEntity.ok().build(); // Always 200
            }

            Map<?, ?> message = (Map<?, ?>) messageObj;

            Object chatObj = message.get("chat");
            if (!(chatObj instanceof Map)) {
                return ResponseEntity.ok().build();
            }

            Map<?, ?> chat = (Map<?, ?>) chatObj;
            Object chatIdObj = chat.get("id");

            if (!(chatIdObj instanceof Number)) {
                return ResponseEntity.ok().build();
            }

            Long chatId = ((Number) chatIdObj).longValue();

            // username (optional)
            String username = null;
            Object fromObj = message.get("from");
            if (fromObj instanceof Map) {
                username = (String) ((Map<?, ?>) fromObj).get("username");
            }

            String text = (String) message.get("text");

            // Handle /start
            if ("/start".equalsIgnoreCase(text)) {
                telegramService.saveUser(chatId, username);
                telegramService.sendToChat(
                        chatId,
                        "✅ You are subscribed for alerts"
                );
            }

        } catch (Exception e) {
            // NEVER let Telegram see an error
            e.printStackTrace();
        }

        // Telegram expects 200 OK always
        return ResponseEntity.ok().build();
    }
}
