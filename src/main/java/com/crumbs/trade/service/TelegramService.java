package com.crumbs.trade.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class TelegramService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${telegram.bot-token}")
    private String botToken;

    @Value("${telegram.base-url}")
    private String baseUrl;

    /**
     * Fetch latest chat_id from Telegram getUpdates
     */
    public String fetchChatId() {
        String url = String.format("%s/bot%s/getUpdates", baseUrl, botToken);

        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            List<Map<String, Object>> result = (List<Map<String, Object>>) response.getBody().get("result");

            if (result != null && !result.isEmpty()) {
                Map<String, Object> latestUpdate = result.get(result.size() - 1); // last message
                Map<String, Object> message = (Map<String, Object>) latestUpdate.get("message");
                if (message != null) {
                    Map<String, Object> chat = (Map<String, Object>) message.get("chat");
                    return chat.get("id").toString(); // can be positive (user) or negative (group)
                }
            }
        }
        throw new RuntimeException("⚠️ No chat_id found. Send a message to your bot first.");
    }

    /**
     * Send message using fetched chat_id
     */
    public boolean sendMessage(String text) {
        String chatId = fetchChatId(); // dynamically fetch

        String url = String.format("%s/bot%s/sendMessage", baseUrl, botToken);

        Map<String, Object> body = Map.of(
            "chat_id", chatId,
            "text", text
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

        return response.getStatusCode().is2xxSuccessful();
    }
}
