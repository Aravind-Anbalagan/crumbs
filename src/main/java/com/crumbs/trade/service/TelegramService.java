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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TelegramService {

    static Logger logger = LoggerFactory.getLogger(TelegramService.class);

    private static final int TELEGRAM_MAX_CHARS = 3800; // safe margin below Telegram's 4096 limit

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

    // =========================================================
    // Initialization
    // =========================================================

    @PostConstruct
    public void initializeChatId() {
        if (configuredChatId != null && !configuredChatId.isEmpty()) {
            cachedChatId = configuredChatId;
            logger.info("✅ Using configured chat_id: {}", cachedChatId);
            return;
        }
        try {
            fetchAndCacheChatId();
        } catch (Exception e) {
            logger.error("⚠️ Could not fetch chat_id: {}", e.getMessage());
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
                        logger.info("✅ Chat ID fetched and cached: {}", cachedChatId);
                        logger.info("💡 Add this to application.properties: telegram.chat-id={}", cachedChatId);
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

    public void refreshChatId() {
        cachedChatId = null;
        fetchAndCacheChatId();
    }

    // =========================================================
    // Core: chunked send — used by ALL send methods
    // =========================================================

    /**
     * Splits text at complete line boundaries (never mid-row) and sends
     * each chunk as a separate message to a single chat ID string.
     * Returns true only if all chunks were sent successfully.
     */
    private boolean sendChunkedToSingleChat(String chatId, String text) {
        List<String> chunks = splitAtLineBoundary(text);
        boolean allSent = true;
        for (int i = 0; i < chunks.size(); i++) {
            try {
                String url = String.format("%s/bot%s/sendMessage", baseUrl, botToken);
                Map<String, Object> body = Map.of(
                    "chat_id",    chatId,
                    "text",       chunks.get(i),
                    "parse_mode", "HTML"
                );
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    logger.info("✅ Chunk {}/{} sent ({} chars)", i + 1, chunks.size(), chunks.get(i).length());
                } else {
                    logger.error("❌ Chunk {}/{} failed. Status: {}", i + 1, chunks.size(), response.getStatusCode());
                    allSent = false;
                }
                if (i < chunks.size() - 1) Thread.sleep(500); // avoid Telegram rate limit
            } catch (Exception e) {
                logger.error("❌ Chunk {}/{} error: {}", i + 1, chunks.size(), e.getMessage());
                allSent = false;
            }
        }
        return allSent;
    }

    /**
     * Splits text at complete line boundaries and sends each chunk
     * to a Long chatId (used for broadcast per-user sends).
     */
    private void sendChunkedToChat(Long chatId, String text) {
        List<String> chunks = splitAtLineBoundary(text);
        for (int i = 0; i < chunks.size(); i++) {
            try {
                sendToChat(chatId, chunks.get(i));
                if (i < chunks.size() - 1) Thread.sleep(500);
            } catch (Exception e) {
                logger.error("Failed to send chunk {}/{} to {}: {}", i + 1, chunks.size(), chatId, e.getMessage());
            }
        }
    }

    /**
     * Splits a message into chunks that are within TELEGRAM_MAX_CHARS,
     * always breaking only on a complete \n line boundary — never mid-row.
     * Automatically closes and reopens <pre> tags across chunk boundaries.
     */
    private List<String> splitAtLineBoundary(String text) {
        List<String> chunks = new ArrayList<>();
        String[] lines = text.split("\n", -1);

        StringBuilder current = new StringBuilder();
        boolean insidePre = false;

        for (String line : lines) {
            // Track whether we are inside a <pre> block
            if (line.contains("<pre>"))  insidePre = true;
            if (line.contains("</pre>")) insidePre = false;

            String lineWithBreak = line + "\n";

            boolean wouldExceed = (current.length() + lineWithBreak.length()) > TELEGRAM_MAX_CHARS;
            boolean chunkHasContent = current.length() > 0;

            if (wouldExceed && chunkHasContent) {
                // Close <pre> if open before sealing this chunk
                String chunk = current.toString();
                if (insidePre) chunk += "</pre>";
                chunks.add(chunk);
                current.setLength(0);
                // Re-open <pre> on the next chunk if we were inside one
                if (insidePre) current.append("<pre>");
            }

            current.append(lineWithBreak);
        }

        // Flush remaining content
        if (current.length() > 0) {
            String last = current.toString();
            if (insidePre && !last.contains("</pre>")) last += "</pre>";
            chunks.add(last);
        }

        return chunks;
    }

    // =========================================================
    // Public send methods — all delegate to chunked helpers
    // =========================================================

    /**
     * Sends a message to the configured/cached single chat.
     * Automatically chunks if the message exceeds 3800 chars.
     */
    public boolean sendMessage(String text) {
        try {
            String chatId = getChatId();
            return sendChunkedToSingleChat(chatId, text);
        } catch (Exception e) {
            logger.error("❌ Failed to send message: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Broadcasts text to all active Telegram users.
     * Automatically chunks long messages per user — never hits 400.
     */
    public int sendBroadcast(String text) {
        List<TelegramUser> users = telegramUserRepo.findByActiveTrue();
        int sent = 0;
        for (TelegramUser user : users) {
            try {
                sendChunkedToChat(user.getChatId(), text);
                sent++;
                Thread.sleep(60); // inter-user delay
            } catch (Exception e) {
                logger.error("Failed to send to {}", user.getChatId(), e);
            }
        }
        return sent;
    }

    /**
     * Low-level single-chunk send to a Long chatId.
     * Used internally by sendChunkedToChat — do NOT call directly with large text.
     */
    public void sendToChat(Long chatId, String text) {
        String url = String.format("%s/bot%s/sendMessage", baseUrl, botToken);
        Map<String, Object> body = Map.of(
            "chat_id",    chatId,
            "text",       text,
            "parse_mode", "HTML"
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        restTemplate.postForEntity(url, entity, String.class);
    }

    // =========================================================
    // Stock alert (PSAR + Heikin-Ashi first-signal stocks)
    // =========================================================

    /**
     * Entry point for the daily bullish/bearish stock alert.
     * Delegates to sendMessage which handles chunking automatically.
     */
    public void sendStockAlert(List<String[]> rows) {
        try {
            String message = buildStockTable(rows);
            boolean sent = sendMessage(message);
            logger.info("📨 Stock alert sent: {}", sent);
        } catch (Exception e) {
            logger.error("❌ Error while sending stock alert: {}", e.getMessage());
        }
    }

    /**
     * Builds a Telegram-friendly fixed-width table for stock alerts.
     */
    public String buildStockTable(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>Stock Details</b>\n\n");
        sb.append("<pre>");

        int[] colWidths = { 12, 8, 8, 8, 6 }; // StockName, Price, Option, Signal, Type

        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            for (int j = 0; j < row.length; j++) {
                String cell = (row[j] == null || row[j].equalsIgnoreCase("null")) ? "-" : row[j];
                int width = (j < colWidths.length) ? colWidths[j] : 10;
                if (cell.length() > width) cell = cell.substring(0, width - 1) + ".";
                sb.append(String.format("%-" + width + "s", cell));
            }
            sb.append("\n");
            if (i == 0) {
                int total = 0; for (int w : colWidths) total += w;
                sb.append("-".repeat(total)).append("\n");
            }
        }

        sb.append("</pre>");
        return sb.toString();
    }

    // =========================================================
    // MA Hierarchy alert
    // =========================================================

    /**
     * Sends the MA hierarchy BUY/SELL stack alert.
     * Builds the full table as one string and delegates to sendMessage —
     * chunking is handled automatically at clean line boundaries.
     */
    public void sendMAHierarchyAlert(List<String[]> rows, int buyCount, int sellCount) {
        try {
            int[]  colWidths  = { 12, 14, 10, 6 };
            int    totalWidth = 0; for (int w : colWidths) totalWidth += w;
            String separator  = "-".repeat(totalWidth) + "\n";

            StringBuilder sb = new StringBuilder();
            sb.append("📈 <b>MA Hierarchy Stack Alert</b>\n")
              .append("🟢 BUY stack: <b>").append(buyCount).append("</b>  |  ")
              .append("🔴 SELL stack: <b>").append(sellCount).append("</b>\n")
              .append("<i>price &gt; EMA9 &gt; EMA21 &gt; MA50 &gt; MA200</i>\n\n")
              .append("<pre>");

            for (int i = 0; i < rows.size(); i++) {
                sb.append(formatRow(rows.get(i), colWidths)).append("\n");
                if (i == 0) sb.append(separator);
            }

            sb.append("</pre>");

            // sendMessage handles all chunking — no size logic needed here
            boolean sent = sendMessage(sb.toString());
            logger.info("MA hierarchy alert sent: {}", sent);

        } catch (Exception e) {
            logger.error("Error building MA hierarchy alert: {}", e.getMessage());
        }
    }

    // =========================================================
    // User management
    // =========================================================

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

    // =========================================================
    // Private helpers
    // =========================================================

    private String formatRow(String[] row, int[] colWidths) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < row.length; j++) {
            String cell  = (row[j] == null || row[j].equalsIgnoreCase("null")) ? "-" : row[j];
            int    width = (j < colWidths.length) ? colWidths[j] : 10;
            if (cell.length() > width) cell = cell.substring(0, width - 1) + ".";
            sb.append(String.format("%-" + width + "s", cell));
        }
        return sb.toString();
    }
}