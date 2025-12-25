package com.crumbs.trade.controller;

import com.crumbs.trade.dto.MessageRequest;
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
    public ResponseEntity<String> send(@RequestBody MessageRequest request) {

        if (request.getText() == null || request.getText().isBlank()) {
            return ResponseEntity.badRequest().body("❌ text is required");
        }

        logger.info("Sending message: {}", request.getText());

        boolean ok = telegramService.sendMessage(request.getText());
        return ResponseEntity.ok(ok ? "✅ Sent!" : "❌ Failed!");
    }

    // ------------------------
    // Broadcast message
    // ------------------------
    @PostMapping("/broadcast")
    public ResponseEntity<String> broadcast(@RequestBody MessageRequest request) {

        if (request.getText() == null || request.getText().isBlank()) {
            return ResponseEntity.badRequest().body("❌ text is required");
        }

        logger.info("Broadcasting message: {}", request.getText());

        int count = telegramService.sendBroadcast(request.getText());
        return ResponseEntity.ok("✅ Broadcast sent to " + count + " users");
    }

    // ------------------------
    // Telegram Webhook (ONLY Telegram calls this)
    // ------------------------
    @PostMapping("/webhook")
    public ResponseEntity<Void> onUpdate(@RequestBody Map<String, Object> update) {

        try {
            Object msgObj = update.get("message");
            if (msgObj == null) {
                msgObj = update.get("edited_message");
            }

            if (!(msgObj instanceof Map)) {
                return ResponseEntity.ok().build();
            }

            Map<?, ?> message = (Map<?, ?>) msgObj;

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

            String username = null;
            Object fromObj = message.get("from");
            if (fromObj instanceof Map) {
                username = (String) ((Map<?, ?>) fromObj).get("username");
            }

            String text = (String) message.get("text");

            logger.info("Telegram update from chatId={}, text={}", chatId, text);

            if ("/start".equalsIgnoreCase(text)) {
                telegramService.saveUser(chatId, username);
                telegramService.sendToChat(
                        chatId,
                        "✅ You are subscribed for alerts"
                );
            }

        } catch (Exception e) {
            logger.error("Error processing Telegram webhook", e);
        }

        // Telegram expects 200 always
        return ResponseEntity.ok().build();
    }
}
