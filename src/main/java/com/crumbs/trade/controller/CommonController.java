package com.crumbs.trade.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.NiftyRepo;
import com.crumbs.trade.service.AngelOneService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

@RestController
@RequestMapping(value = "/common")
public class CommonController {

    private static final Logger log = LoggerFactory.getLogger(CommonController.class);
    private static final String INSTRUMENTS_FILENAME = "Instruments.txt";
    private static final long   MAX_FILE_SIZE_MB     = 100;
    private static final int    BATCH_SIZE           = 1000;

    // FIX: date formatter reused — no need to create on every row
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss");

    @Value("${angelone.api.url:https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json}")
    private String angelOneApiUrl;

    @Value("${app.data.path:/app/data}")
    private String appDataPath;

    @Value("${app.local.resources.path:src/main/resources}")
    private String localResourcesPath;
    @Value("${spring.datasource.url}")
    private String dbUrl;
    @Autowired private NiftyRepo       niftyRepo;
    @Autowired private RestTemplate    restTemplate;
    @Autowired private IndexesRepo     indexesRepo;
    @Autowired private AngelOneService angelOneService;
    @Autowired private JdbcTemplate    jdbcTemplate;
    @Autowired private EntityManager   entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ================================================================
    // ===================== ENDPOINTS ================================
    // ================================================================
    

    @GetMapping("/db-check")
    public String dbCheck() {
        return dbUrl;
    }
    
    @GetMapping("/debug/ip")
    public String getServerIp() throws Exception {
        RestTemplate rt = new RestTemplate();
        return rt.getForObject("https://ifconfig.me", String.class);
    }
    
    @GetMapping(value = "/clear")
    public ResponseEntity<String> deleteOrders() {
        try {
            log.info("Manual trigger: Delete All Data");
            angelOneService.deleteOrders();
            return ResponseEntity.ok("Completed successfully");
        } catch (Exception e) {
            log.error("Error deleting orders", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void clearScheduled() {
        try {
            log.info("Scheduled job: Delete All Data");
            angelOneService.deleteOrders();
            log.info("Scheduled clear completed successfully");
        } catch (Exception e) {
            log.error("Error in scheduled clear job", e);
        }
    }

    @PostMapping("/download-script-locally")
    public ResponseEntity<String> downloadScriptsLocal() {
        try {
            log.info("Starting full download");
            String downloadResult = downloadScriptFromAngelOne();
            if (downloadResult.contains("Error") || downloadResult.contains("❌")) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(downloadResult);
            }
            log.info("Full process completed successfully");
            return ResponseEntity.ok("Completed: " + downloadResult);
        } catch (Exception e) {
            log.error("Error in download script locally process", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }

    @PostMapping("/extract-script-locally")
    public ResponseEntity<String> extractScriptLocally() {
        try {
            log.info("Starting extraction process");
            String moveResult = moveInstrumentsFileToFlyVolume();
            if (moveResult.contains("not found")) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(moveResult);
            }
            String extractResult = getAllIndexToken();
            log.info("Extracted successfully");
            return ResponseEntity.ok("Completed: " + extractResult);
        } catch (Exception e) {
            log.error("Error in extract script locally process", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }
/*
    @PostMapping("/extract-script-OnFly")
    public ResponseEntity<String> extractScript() {
        try {
            log.info("Starting extraction from Fly volume");
            String moveResult = moveInstrumentsFileToFlyVolume();
            if (moveResult.contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(moveResult);
            }
            String extractResult = getAllIndexToken();
            return ResponseEntity.ok("Extraction completed: " + extractResult);
        } catch (Exception e) {
            log.error("Error in extract script process", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }
*/
    // ================================================================
    // ===================== CORE LOGIC ================================
    // ================================================================

    /**
     * Download instruments file from AngelOne API
     */
    private String downloadScriptFromAngelOne() {
        log.info("⬇️ Starting download of AngelOne Script File...");

        Path localTarget = Paths.get(localResourcesPath, INSTRUMENTS_FILENAME);
        log.info("📁 Local resource output path: {}", localTarget.toAbsolutePath());

        try {
            Path parent = localTarget.getParent();
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent);
                log.info("📂 Created directory: {}", parent.toAbsolutePath());
            }

            Path tmpTarget = parent.resolve(INSTRUMENTS_FILENAME + ".tmp");

            RequestCallback requestCallback = request -> {
                HttpHeaders headers = request.getHeaders();
                headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0");
                headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
            };

            ResponseExtractor<Void> responseExtractor = response -> {
                HttpStatus status = HttpStatus.resolve(response.getRawStatusCode());
                log.info("🌐 AngelOne API HTTP Status: {}", status);

                if (status == null || !status.is2xxSuccessful()) {
                    throw new IOException("❌ AngelOne returned non-2xx: " + response.getRawStatusCode());
                }

                try (InputStream is = response.getBody()) {
                    if (is == null) throw new IOException("❌ AngelOne response body is NULL");

                    long bytesCopied = Files.copy(is, tmpTarget, StandardCopyOption.REPLACE_EXISTING);
                    long fileSizeMB  = bytesCopied / (1024 * 1024);
                    log.info("📊 Downloaded file size: {} MB", fileSizeMB);

                    if (fileSizeMB > MAX_FILE_SIZE_MB) {
                        Files.deleteIfExists(tmpTarget);
                        throw new IOException("❌ File size exceeds max: " + fileSizeMB + " MB");
                    }

                    Files.move(tmpTarget, localTarget,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);

                    log.info("✅ AngelOne Script downloaded successfully.");
                }
                return null;
            };

            restTemplate.execute(angelOneApiUrl, HttpMethod.GET, requestCallback, responseExtractor);

            String msg = "✅ Saved to: " + localTarget.toAbsolutePath();
            log.info(msg);
            return msg;

        } catch (Exception ex) {
            log.error("❌ Error downloading file from AngelOne", ex);
            return "Error: " + ex.getMessage();
        }
    }

    /**
     * Extract instruments from JSON file and save to DB using JDBC batch insert.
     *
     * FIX 1: TRUNCATE instead of deleteAll()   → instant clear
     * FIX 2: JDBC batchUpdate instead of saveAll() → 1 network call per 1000 rows
     * FIX 3: SQL columns now match Indexes entity exactly
     * FIX 4: Timing added to log actual performance
     */
    @Transactional
    private String getAllIndexToken() throws JsonProcessingException, IOException {

        log.info("📖 Starting extraction + DB insert from {}/{}", appDataPath, INSTRUMENTS_FILENAME);

        // ── Validate file ────────────────────────────────────────────────
        Path inputPath = Paths.get(appDataPath, INSTRUMENTS_FILENAME);
        if (!Files.exists(inputPath)) {
            log.error("❌ Input file NOT found: {}", inputPath.toAbsolutePath());
            throw new IOException("Input file not found at: " + inputPath.toAbsolutePath());
        }

        long fileSizeBytes = Files.size(inputPath);
        long fileSizeMB    = fileSizeBytes / (1024 * 1024);
        log.info("📁 File size: {} MB", fileSizeMB);

        if (fileSizeMB > MAX_FILE_SIZE_MB) {
            throw new IOException("File size exceeds max: " + fileSizeMB + " MB");
        }

        // ── FIX 1: TRUNCATE — instant vs row-by-row deleteAll() ──────────
        log.info("🗑️ Clearing Indexes table...");
        // FIX: table name matches @Table(name = "Indexes") in entity
        jdbcTemplate.execute("TRUNCATE TABLE indexes RESTART IDENTITY CASCADE");
        log.info("✅ Indexes table cleared");

        // ── Load filter list ─────────────────────────────────────────────
        List<String> optionNameList = niftyRepo.getAllNames();
        log.info("📋 Option names for filtering: {}", optionNameList.size());

        // ── Parse JSON ───────────────────────────────────────────────────
        JsonNode rootNode = objectMapper.readTree(inputPath.toFile());
        log.info("📄 Total JSON records: {}", rootNode.size());

        // ── Collect matching records ─────────────────────────────────────
        List<Indexes> indexesToSave = new ArrayList<>();
        for (JsonNode node : rootNode) {
            if (shouldIncludeIndex(node, optionNameList)) {
                indexesToSave.add(createIndexFromNode(node));
            }
        }

        int totalRecords = indexesToSave.size();
        int totalBatches = (int) Math.ceil((double) totalRecords / BATCH_SIZE);
        log.info("📋 Records matched: {} | Batches: {}", totalRecords, totalBatches);

        // ── FIX 2 + FIX 3: JDBC batch insert with correct columns ────────
        // Columns match Indexes entity exactly — no instrument_type, exch_seg, tick_size
        // (those fields don't exist in your entity)
        String now = LocalDateTime.now().format(DATE_FMT);

        String sql = """
                INSERT INTO indexes
                    (name, token, symbol, exchange, expiry, strike, lotsize,
                     from_date, to_date, time_frame, volume, active, created_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < totalRecords; i += BATCH_SIZE) {
            List<Indexes> chunk = indexesToSave.subList(
                    i, Math.min(i + BATCH_SIZE, totalRecords));

            jdbcTemplate.batchUpdate(sql, chunk, chunk.size(), (ps, idx) -> {
                ps.setString(1,  idx.getName());
                ps.setString(2,  idx.getToken());
                ps.setString(3,  idx.getSymbol());
                ps.setString(4,  idx.getExchange());
                ps.setString(5,  idx.getExpiry());
                ps.setString(6,  idx.getStrike());
                ps.setInt   (7,  idx.getLotsize());
                ps.setString(8,  idx.getFromDate());   // may be null — that's fine
                ps.setString(9,  idx.getToDate());     // may be null — that's fine
                ps.setString(10, idx.getTimeFrame());  // may be null — that's fine
                ps.setString(11, idx.getVolume());     // may be null — that's fine
                ps.setString(12, idx.getActive());     // may be null — that's fine
                ps.setString(13, now);                 // created_date — same for all rows in this run
            });

            log.info("💾 Batch {}/{} done ({} records inserted)",
                    (i / BATCH_SIZE) + 1,
                    totalBatches,
                    Math.min(i + BATCH_SIZE, totalRecords));
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("✅ Insert complete — {} records in {}s", totalRecords, elapsed / 1000);

        // ── Delete source file ───────────────────────────────────────────
        deleteFile(inputPath);

        return "Created " + totalRecords + " records in " + (elapsed / 1000) + " seconds";
    }

    // ================================================================
    // ===================== HELPERS ==================================
    // ================================================================

    private boolean shouldIncludeIndex(JsonNode node, List<String> optionNameList) {
        String name    = node.path("name").asText();
        String symbol  = node.path("symbol").asText();
        String exchSeg = node.path("exch_seg").asText();

        return (!name.matches("[a-zA-Z ]*\\d+.*")
                    && symbol.contains("-EQ")
                    && exchSeg.equals("NSE"))
                || exchSeg.equals("BSE")
                || name.equals("NIFTY")
                || name.equals("CRUDEOIL")
                || name.equals("NATURALGAS")
                || name.equals("INDIA VIX")
                || name.equals("SILVERM")
                || optionNameList.contains(name);
    }

    private Indexes createIndexFromNode(JsonNode node) {
        Indexes indexes = new Indexes();
        indexes.setName(node.path("name").asText());
        indexes.setToken(node.path("token").asText());
        indexes.setSymbol(node.path("symbol").asText());
        indexes.setExchange(node.path("exch_seg").asText());
        indexes.setExpiry(node.path("expiry").asText());
        indexes.setStrike(node.path("strike").asText());
        indexes.setLotsize(node.path("lotsize").asInt());
        // fromDate, toDate, timeFrame, volume, active — not in JSON, will be null
        return indexes;
    }

    private String moveInstrumentsFileToFlyVolume() throws IOException {
        log.info("📦 Copying {} from resources to {}/...", INSTRUMENTS_FILENAME, appDataPath);

        ClassPathResource resource = new ClassPathResource(INSTRUMENTS_FILENAME);
        if (!resource.exists()) {
            log.error("❌ {} NOT found in resources!", INSTRUMENTS_FILENAME);
            throw new IOException(INSTRUMENTS_FILENAME + " not found in resources!");
        }

        Path target = Paths.get(appDataPath, INSTRUMENTS_FILENAME);
        Files.createDirectories(target.getParent());

        try (InputStream is = resource.getInputStream()) {
            long bytesCopied = Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("✅ Copied {} bytes to {}", bytesCopied, target.toAbsolutePath());
        }

        return "Copied " + INSTRUMENTS_FILENAME + " to: " + target.toAbsolutePath();
    }

    private void deleteFile(Path path) throws IOException {
        if (Files.deleteIfExists(path)) {
            log.info("🗑️ Deleted: {}", path.toAbsolutePath());
        } else {
            log.warn("⚠️ File not found for deletion: {}", path.toAbsolutePath());
        }
    }
}