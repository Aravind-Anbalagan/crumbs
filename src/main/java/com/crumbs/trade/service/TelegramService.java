package com.crumbs.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.crumbs.trade.entity.Alert;
import com.crumbs.trade.entity.TelegramUser;
import com.crumbs.trade.repo.AlertConfigRepo;
import com.crumbs.trade.repo.AlertRepo;
import com.crumbs.trade.repo.TelegramUserRepo;

import javax.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TelegramService {

    static Logger logger = LoggerFactory.getLogger(TelegramService.class);
    private static final String NEW_CHAT_ID = "8948124698";
    private static final int TELEGRAM_MAX_CHARS = 3800;

    private static final Pattern VALID_TAG = Pattern.compile(
        "<(/?(b|i|u|s|code|pre)|a(\\s+href=\"[^\"]*\")?|/a)>",
        Pattern.CASE_INSENSITIVE
    );

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${telegram.bot-token}")
    private String botToken;

    @Value("${telegram.base-url}")
    private String baseUrl;

    @Value("${telegram.chat-id:}")
    private String configuredChatId;

    @Value("${telegram.new-bot-token}")
    private String newBotToken;

    @Value("${telegram.new-chat-id}")
    private String newChatId;

    private String cachedChatId;
    private Long updateOffset = 0L;

    @Autowired TelegramUserRepo telegramUserRepo;
    @Autowired AlertConfigRepo  alertConfigRepo;
    @Autowired AlertRepo        alertRepo;

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
                List<Map<String, Object>> result =
                        (List<Map<String, Object>>) response.getBody().get("result");
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
                        logger.info("💡 Add this to application.properties: telegram.chat-id={}",
                                cachedChatId);
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
    // Core: chunked send
    // =========================================================

    private boolean sendChunkedToSingleChat(String chatId, String text) {
        List<String> chunks = splitAtLineBoundary(text);
        boolean allSent = true;
        for (int i = 0; i < chunks.size(); i++) {
            try {
                String safe = sanitizeHtml(chunks.get(i));
                String url  = String.format("%s/bot%s/sendMessage", baseUrl, botToken);
                Map<String, Object> body = Map.of(
                    "chat_id",    chatId,
                    "text",       safe,
                    "parse_mode", "HTML"
                );
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    logger.info("✅ Chunk {}/{} sent ({} chars)", i + 1, chunks.size(), safe.length());
                } else {
                    logger.error("❌ Chunk {}/{} failed. Status: {}",
                            i + 1, chunks.size(), response.getStatusCode());
                    allSent = false;
                }
                if (i < chunks.size() - 1) Thread.sleep(500);
            } catch (Exception e) {
                logger.error("❌ Chunk {}/{} error: {}", i + 1, chunks.size(), e.getMessage());
                allSent = false;
            }
        }
        return allSent;
    }

    private void sendChunkedToChat(Long chatId, String text) {
        List<String> chunks = splitAtLineBoundary(text);
        for (int i = 0; i < chunks.size(); i++) {
            try {
                sendToChat(chatId, chunks.get(i));
                if (i < chunks.size() - 1) Thread.sleep(500);
            } catch (Exception e) {
                logger.error("Failed to send chunk {}/{} to {}: {}",
                        i + 1, chunks.size(), chatId, e.getMessage());
            }
        }
    }

    private List<String> splitAtLineBoundary(String text) {
        List<String> chunks = new ArrayList<>();
        String[] lines = text.split("\n", -1);

        StringBuilder current  = new StringBuilder();
        boolean       insidePre = false;

        for (String line : lines) {
            if (line.contains("<pre>"))  insidePre = true;
            if (line.contains("</pre>")) insidePre = false;

            String lineWithBreak = line + "\n";
            boolean wouldExceed  = (current.length() + lineWithBreak.length()) > TELEGRAM_MAX_CHARS;
            boolean hasContent   = current.length() > 0;

            if (wouldExceed && hasContent) {
                String chunk = current.toString();
                if (insidePre) chunk += "</pre>";
                chunks.add(chunk);
                current.setLength(0);
                if (insidePre) current.append("<pre>");
            }

            current.append(lineWithBreak);
        }

        if (current.length() > 0) {
            String last = current.toString();
            if (insidePre && !last.contains("</pre>")) last += "</pre>";
            chunks.add(last);
        }

        return chunks;
    }

    // =========================================================
    // Public send methods
    // =========================================================

    public boolean sendMessage(String text) {
        try {
            String chatId = getChatId();
            return sendChunkedToSingleChat(chatId, text);
        } catch (Exception e) {
            logger.error("❌ Failed to send message: {}", e.getMessage());
            return false;
        }
    }

    public int sendBroadcast(String text) {
        List<TelegramUser> users = telegramUserRepo.findByActiveTrue();
        int sent = 0;
        for (TelegramUser user : users) {
            try {
                sendChunkedToChat(user.getChatId(), text);
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
            "chat_id",    chatId,
            "text",       sanitizeHtml(text),
            "parse_mode", "HTML"
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        restTemplate.postForEntity(url, entity, String.class);
    }

    // =========================================================
    // Stock alert
    // =========================================================

    public void sendStockAlert(List<String[]> rows) {
        try {
            String  message = buildfnoStockTable(rows);
            boolean sent    = sendToNewChat(message);
            logger.info("📨 Stock alert sent: {}", sent);
            saveAlertIfEnabled("STOCK_ALERT", message, "INFO", sent);
        } catch (Exception e) {
            logger.error("❌ Error while sending stock alert: {}", e.getMessage());
        }
    }

    public String buildStockTable(List<String[]> rows) {
        StringBuilder sb        = new StringBuilder();
        int[]         colWidths = { 12, 8, 8, 8, 6 };

        sb.append("📊 <b>Stock Details</b>\n\n").append("<pre>");

        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            for (int j = 0; j < row.length; j++) {
                String raw   = (row[j] == null || row[j].equalsIgnoreCase("null")) ? "-" : row[j];
                String cell  = escapeHtml(raw);
                int    width = (j < colWidths.length) ? colWidths[j] : 10;
                if (cell.length() > width) cell = cell.substring(0, width - 1) + ".";
                sb.append(String.format("%-" + width + "s", cell));
            }
            sb.append("\n");
            if (i == 0) {
                int total = 0;
                for (int w : colWidths) total += w;
                sb.append("-".repeat(total)).append("\n");
            }
        }

        sb.append("</pre>");
        return sb.toString();
    }
    public String buildfnoStockTable(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>F&O Alert — Stocks Moving &gt; 5%</b>\n");

        // rows.get(0) is the header {"Stock","PrevClose","Change%","S/R Zone"} — skip it
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String name       = escapeHtml(safe(row[0]));
            String prevClose  = escapeHtml(safe(row[1]));
            String changePct  = escapeHtml(safe(row[2]));
            String zoneLabel  = escapeHtml(safe(row[3]));

            boolean isGain = changePct.startsWith("+");
            String arrow = isGain ? "🟢" : "🔴";

            sb.append("\n").append(arrow).append(" <b>").append(name).append("</b>\n")
                    .append("   Prev: ₹").append(prevClose)
                    .append("  |  Chg: <code>").append(changePct).append("</code>\n")
                    .append("   ").append(zoneLabel).append("\n");
        }

        return sb.toString();
    }

    private String safe(String value) {
        return (value == null || value.equalsIgnoreCase("null")) ? "-" : value;
    }
    // =========================================================
    // MA Hierarchy alert
    // =========================================================

    public void sendMAHierarchyAlert(List<String[]> rows, int buyCount, int sellCount) {
        try {
            int[]  colWidths  = { 12, 14, 10, 6 };
            int    totalWidth = 0;
            for (int w : colWidths) totalWidth += w;
            String separator = "-".repeat(totalWidth) + "\n";

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

            boolean sent = sendMessage(sb.toString());
            logger.info("MA hierarchy alert sent: {}", sent);
            saveAlertIfEnabled("MA_HIERARCHY", sb.toString(),
                    buyCount > 0 ? "BUY" : "SELL", sent);

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
    // saveAlertIfEnabled — three overloads, one implementation
    // =========================================================

    /**
     * Overload 1 — non-symbol alerts (STOCK_ALERT, MA_HIERARCHY).
     * No symbol, no price fields.
     */
    public void saveAlertIfEnabled(String strategyName, String message,
                                    String signalType, boolean sent) {
        saveAlertFull(strategyName, null, message, signalType, sent,
                      null, null, null, null, null);
    }

    /**
     * Overload 2 — symbol alerts without price data.
     * Used when only the symbol is known (e.g. crossover-only saves).
     */
    public void saveAlertIfEnabled(String strategyName, String symbol,
                                    String message, String signalType, boolean sent) {
        saveAlertFull(strategyName, symbol, message, signalType, sent,
                      null, null, null, null, null);
    }

    /**
     * Overload 3 — full VWAP dominance / crossover alert.
     * Populates symbol, strike, cePrice, pePrice, ceVwap, peVwap so that
     * DominanceService can read these fields directly from the Alert row
     * for SL checks without any live market call.
     */
    public void saveAlertIfEnabled(String strategyName, String symbol,
                                    String message,  String signalType, boolean sent,
                                    Integer strike,
                                    Double cePrice,  Double pePrice,
                                    Double ceVwap,   Double peVwap) {
        saveAlertFull(strategyName, symbol, message, signalType, sent,
                      strike, cePrice, pePrice, ceVwap, peVwap);
    }

    /**
     * Single implementation — all three overloads delegate here.
     * Null-safe: any field not supplied by the caller is stored as null.
     */
    private void saveAlertFull(String strategyName, String symbol,
                                String message,      String signalType, boolean sent,
                                Integer strike,
                                Double cePrice,      Double pePrice,
                                Double ceVwap,       Double peVwap) {
        try {
            Alert alert = Alert.builder()
                    .strategyName(strategyName)
                    .symbol(symbol)           // null for STOCK_ALERT / MA_HIERARCHY
                    .message(message)
                    .signalType(signalType)
                    .status(sent ? "SENT" : "FAILED")
                    .sentAt(LocalDateTime.now())
                    .strike(strike)           // null unless overload 3 used
                    .cePrice(cePrice)         // null unless overload 3 used
                    .pePrice(pePrice)         // null unless overload 3 used
                    .ceVwap(ceVwap)           // null unless overload 3 used
                    .peVwap(peVwap)           // null unless overload 3 used
                    .build();

            alertRepo.save(alert);
            logger.info("💾 Alert saved — strategy={} symbol={} signal={}",
                    strategyName, symbol, signalType);

        } catch (Exception e) {
            logger.error("⚠️ Could not save alert for {} {}: {}",
                    strategyName, symbol, e.getMessage());
        }
    }

    // =========================================================
    // Private helpers
    // =========================================================

    private String formatRow(String[] row, int[] colWidths) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < row.length; j++) {
            String raw   = (row[j] == null || row[j].equalsIgnoreCase("null")) ? "-" : row[j];
            String cell  = escapeHtml(raw);
            int    width = (j < colWidths.length) ? colWidths[j] : 10;
            if (cell.length() > width) cell = cell.substring(0, width - 1) + ".";
            sb.append(String.format("%-" + width + "s", cell));
        }
        return sb.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "-";
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private String sanitizeHtml(String text) {
        if (text == null || text.isEmpty()) return text;

        final String PLACEHOLDER = "\u0000LT\u0000";

        String result = text.replace("<", PLACEHOLDER);

        result = result
            .replace(PLACEHOLDER + "b>",      "<b>")
            .replace(PLACEHOLDER + "/b>",     "</b>")
            .replace(PLACEHOLDER + "i>",      "<i>")
            .replace(PLACEHOLDER + "/i>",     "</i>")
            .replace(PLACEHOLDER + "u>",      "<u>")
            .replace(PLACEHOLDER + "/u>",     "</u>")
            .replace(PLACEHOLDER + "s>",      "<s>")
            .replace(PLACEHOLDER + "/s>",     "</s>")
            .replace(PLACEHOLDER + "code>",   "<code>")
            .replace(PLACEHOLDER + "/code>",  "</code>")
            .replace(PLACEHOLDER + "pre>",    "<pre>")
            .replace(PLACEHOLDER + "/pre>",   "</pre>")
            .replace(PLACEHOLDER + "/a>",     "</a>");

        result = Pattern.compile(Pattern.quote(PLACEHOLDER) + "a(\\s+href=\"[^\"]*\")>")
                        .matcher(result)
                        .replaceAll("<a$1>");

        result = result.replace(PLACEHOLDER, "&lt;");

        return result;
    }

    public boolean sendToNewChat(String text) {
        try {
            return sendChunkedToSingleChatWithToken(newBotToken, newChatId, text);
        } catch (Exception e) {
            logger.error("❌ Failed to send message to new bot: {}", e.getMessage());
            return false;
        }
    }

    private boolean sendChunkedToSingleChatWithToken(String token, String chatId, String text) {
        List<String> chunks = splitAtLineBoundary(text);
        boolean allSent = true;
        for (int i = 0; i < chunks.size(); i++) {
            try {
                String safe = sanitizeHtml(chunks.get(i));
                String url  = String.format("%s/bot%s/sendMessage", baseUrl, token);
                Map<String, Object> body = Map.of(
                        "chat_id",    chatId,
                        "text",       safe,
                        "parse_mode", "HTML"
                );
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    logger.info("✅ New-bot chunk {}/{} sent ({} chars)", i + 1, chunks.size(), safe.length());
                } else {
                    logger.error("❌ New-bot chunk {}/{} failed. Status: {}", i + 1, chunks.size(), response.getStatusCode());
                    allSent = false;
                }
                if (i < chunks.size() - 1) Thread.sleep(500);
            } catch (Exception e) {
                logger.error("❌ New-bot chunk {}/{} error: {}", i + 1, chunks.size(), e.getMessage());
                allSent = false;
            }
        }
        return allSent;
    }
}