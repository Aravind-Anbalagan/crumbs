package com.crumbs.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

@Service
public class TelegramService {
	 static Logger logger = LoggerFactory.getLogger(TelegramService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    
    @Value("${telegram.bot-token}")
    private String botToken;
    
    @Value("${telegram.base-url}")
    private String baseUrl;
    
    @Value("${telegram.chat-id:}") // Optional: can be hardcoded in properties
    private String configuredChatId;
    
    private String cachedChatId;
    private Long updateOffset = 0L;
    
    @PostConstruct
    public void initializeChatId() {
        // Use configured chat_id if provided
        if (configuredChatId != null && !configuredChatId.isEmpty()) {
            cachedChatId = configuredChatId;
            logger.info("✅ Using configured chat_id: " + cachedChatId);
            return;
        }
        
        // Otherwise, try to fetch from Telegram
        try {
            fetchAndCacheChatId();
        } catch (Exception e) {
        	logger.error("⚠️ Could not fetch chat_id: " + e.getMessage());
        	logger.error("💡 Send a message to your bot or configure telegram.chat-id in properties");
        }
    }
    
    private void fetchAndCacheChatId() {
        String url = String.format("%s/bot%s/getUpdates?offset=%d", baseUrl, botToken, updateOffset);
        
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> result = (List<Map<String, Object>>) response.getBody().get("result");
                
                if (result != null && !result.isEmpty()) {
                    Map<String, Object> latestUpdate = result.get(result.size() - 1);
                    updateOffset = ((Number) latestUpdate.get("update_id")).longValue() + 1;
                    
                    Map<String, Object> message = (Map<String, Object>) latestUpdate.get("message");
                    if (message != null) {
                        Map<String, Object> chat = (Map<String, Object>) message.get("chat");
                        cachedChatId = chat.get("id").toString();
                        logger.info("✅ Chat ID fetched and cached: " + cachedChatId);
                        logger.info("💡 Add this to application.properties: telegram.chat-id=" + cachedChatId);
                        return;
                    }
                }
            }
            
            throw new RuntimeException("No updates found");
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch chat_id from Telegram: " + e.getMessage());
        }
    }
    
    public String getChatId() {
        if (cachedChatId == null || cachedChatId.isEmpty()) {
            fetchAndCacheChatId();
        }
        return cachedChatId;
    }
    
    public boolean sendMessage(String text) {
        try {
            String chatId = getChatId();
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
            
        } catch (Exception e) {
        	logger.error("❌ Failed to send message: " + e.getMessage());
            return false;
        }
    }
    
    public void refreshChatId() {
        cachedChatId = null;
        fetchAndCacheChatId();
    }
}