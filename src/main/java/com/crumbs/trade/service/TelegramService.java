package com.crumbs.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.crumbs.trade.entity.TelegramUser;
import com.crumbs.trade.repo.TelegramUserRepo;

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

    @Value("${telegram.chat-id:}")
    private String configuredChatId;

    private String cachedChatId;
    private Long updateOffset = 0L;

    @Autowired TelegramUserRepo telegramUserRepo;

    @PostConstruct
    public void initializeChatId() {
        if (configuredChatId != null && !configuredChatId.isEmpty()) {
            cachedChatId = configuredChatId;
            logger.info("✅ Using configured chat_id: " + cachedChatId);
            return;
        }
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
                    for (Map<String, Object> update : result) {
                        Long currentUpdateId = ((Number) update.get("update_id")).longValue();
                        updateOffset = Math.max(updateOffset, currentUpdateId + 1);
                        Map<String, Object> message = (Map<String, Object>) update.get("message");
                        if (message != null) {
                            Map<String, Object> chat = (Map<String, Object>) message.get("chat");
                            if (chat != null && chat.get("id") != null) {
                                cachedChatId = chat.get("id").toString();
                            }
                        }
                    }
                    if (cachedChatId != null) {
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
                "text", text,
                "parse_mode", "HTML"
            );
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("✅ Message sent successfully: " + text.substring(0, Math.min(50, text.length())));
                return true;
            } else {
                logger.error("❌ Failed to send message. Status: " + response.getStatusCode());
                return false;
            }
        } catch (Exception e) {
            logger.error("❌ Failed to send message: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public void refreshChatId() {
        cachedChatId = null;
        fetchAndCacheChatId();
    }

    // ─── Stock Notification Methods ──────────────────────────────────────────

    /**
     * Equivalent to sendEmail() — entry point for stock alerts
     */
    public void sendStockAlert(List<String[]> rows) {
        try {
            //rows.add(0, new String[]{"Stock Name", "Price", "Option", "Signal", "Type"});
            String message = buildStockTable(rows);
            boolean sent = sendMessage(message);
            logger.info("📨 Stock alert sent to {} users", sent);
        } catch (Exception e) {
            logger.error("❌ Error while sending stock alert: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Equivalent to buildHtmlTable() — builds a Telegram-friendly table
     */
    public String buildStockTable(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>Stock Details</b>\n\n");
        sb.append("<pre>");

        int[] colWidths = {12, 8, 8, 8, 6}; // StockName, Price, Option, Signal, Type

        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            for (int j = 0; j < row.length; j++) {
                // Replace null or "null" string with "-"
                String cell = (row[j] == null || row[j].equalsIgnoreCase("null")) ? "-" : row[j];
                int width = (j < colWidths.length) ? colWidths[j] : 10;
                sb.append(String.format("%-" + width + "s", cell));
            }
            sb.append("\n");

            // Separator line after header
            if (i == 0) {
                int totalWidth = 0;
                for (int w : colWidths) totalWidth += w;
                sb.append("-".repeat(totalWidth)).append("\n");
            }
        }

        sb.append("</pre>");
        return sb.toString();
    }

    // ─── Broadcast & User Management (unchanged) ─────────────────────────────

    public int sendBroadcast(String text) {
        List<TelegramUser> users = telegramUserRepo.findByActiveTrue();
        int sent = 0;
        for (TelegramUser user : users) {
            try {
                sendToChat(user.getChatId(), text);
                sent++;
                Thread.sleep(60);
            } catch (Exception e) {
                logger.error("Failed to send to {}", user.getChatId(), e);
            }
        }
        return sent;
    }

    public void sendToChat(Long chatId, String text) {
        String url = String.format("%s/bot%s/sendMessage", baseUrl, botToken);
        Map<String, Object> body = Map.of(
            "chat_id", chatId,
            "text", text,
            "parse_mode", "HTML"
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        restTemplate.postForEntity(url, entity, String.class);
    }

    public void saveUser(Long chatId, String username) {
        telegramUserRepo.findById(chatId).ifPresentOrElse(
            user -> {
                if (Boolean.FALSE.equals(user.getActive())) {
                    user.setActive(true);
                    telegramUserRepo.save(user);
                }
            },
            () -> {
                TelegramUser user = TelegramUser.builder()
                        .chatId(chatId)
                        .username(username)
                        .active(true)
                        .build();
                telegramUserRepo.save(user);
            }
        );
    }
}