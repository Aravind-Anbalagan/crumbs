package com.crumbs.trade.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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

@RestController
@RequestMapping(value = "/common")
public class CommonController {
    
    private static final Logger log = LoggerFactory.getLogger(CommonController.class);
    private static final String INSTRUMENTS_FILENAME = "Instruments.txt";
    private static final long MAX_FILE_SIZE_MB = 100;
    private static final int BATCH_SIZE = 1000;

    @Value("${angelone.api.url:https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json}")
    private String angelOneApiUrl;

    @Value("${app.data.path:/app/data}")
    private String appDataPath;

    @Value("${app.local.resources.path:src/main/resources}")
    private String localResourcesPath;

    @Autowired
    private NiftyRepo niftyRepo;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private IndexesRepo indexesRepo;

    @Autowired
    private AngelOneService angelOneService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Manually trigger deletion of all orders
     */
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

    /**
     * Scheduled job: Clear DB every weekday at 9:10 AM IST
     */
    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void clearScheduled() {
        try {
            log.info("Scheduled job: Delete All Data");
            angelOneService.deleteOrders();
            log.info("Scheduled clear completed successfully");
        } catch (Exception e) {
            log.error("Error in scheduled clear job", e);
        }
    }

    /**
     * Scheduled job: Fetch tokens every Friday at 11 PM IST
     */
    @Scheduled(cron = "0 0 23 * * FRI", zone = "Asia/Kolkata")
    public void fetchTokensScheduled() {
        try {
            log.info("Scheduled job: Fetching tokens");
            getAllIndexToken();
            log.info("Scheduled token fetch completed successfully");
        } catch (Exception e) {
            log.error("Error in scheduled token fetch", e);
        }
    }

    /**
     * Download scripts from AngelOne, move to volume, and extract to DB
     */
    @PostMapping("/download-script-locally")
    public ResponseEntity<String> downloadScriptsLocal() {
        try {
            log.info("Starting full download");
            
            // Step 1: Download
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
    /**
     * Download scripts from AngelOne, move to volume, and extract to DB
     */
    @PostMapping("/extract-script-locally")
    public ResponseEntity<String> extractScriptLocally() {
        try {
            log.info("Starting extraction process");
           
            
            // Step 1: Move to volume
            String moveResult = moveInstrumentsFileToFlyVolume();
            if (moveResult.contains("not found")) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(moveResult);
            }
            
            // Step 2: Extract and save to DB
            String extractResult = getAllIndexToken();
            
            log.info("Extracted successfully");
            return ResponseEntity.ok("Completed: " + extractResult);
            
        } catch (Exception e) {
            log.error("Error in download script locally process", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }
    
    
    

    /**
     * Extract scripts from existing file on Fly volume
     */
    @PostMapping("/extract-script-OnFly")
    public ResponseEntity<String> extractScript() {
        try {
            log.info("Starting extraction from Fly volume");
            
            // Copy the file
            String moveResult = moveInstrumentsFileToFlyVolume();
            if (moveResult.contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(moveResult);
            }
            
            // Extract the file and store in DB
            String extractResult = getAllIndexToken();
            
            return ResponseEntity.ok("Extraction completed: " + extractResult);
            
        } catch (Exception e) {
            log.error("Error in extract script process", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }

    /**
     * Download the instruments file from AngelOne API
     */
    private String downloadScriptFromAngelOne() {
        log.info("⬇️ Starting download of AngelOne Script File...");

        Path localTarget = Paths.get(localResourcesPath, INSTRUMENTS_FILENAME);
        log.info("📁 Local resource output path: {}", localTarget.toAbsolutePath());

        try {
            // Ensure parent directory exists
            Path parent = localTarget.getParent();
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent);
                log.info("📂 Created directory: {}", parent.toAbsolutePath());
            }

            Path tmpTarget = parent.resolve(INSTRUMENTS_FILENAME + ".tmp");
            log.info("📝 Temporary write target: {}", tmpTarget.toAbsolutePath());

            // Request callback to set headers
            RequestCallback requestCallback = request -> {
                HttpHeaders headers = request.getHeaders();
                headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0");
                headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
                log.debug("🌐 AngelOne request headers set.");
            };

            // Response extractor to handle download
            ResponseExtractor<Void> responseExtractor = response -> {
                HttpStatus status = HttpStatus.resolve(response.getRawStatusCode());
                log.info("🌐 AngelOne API HTTP Status: {}", status);

                if (status == null || !status.is2xxSuccessful()) {
                    throw new IOException("❌ AngelOne returned non-2xx: " + response.getRawStatusCode());
                }

                try (InputStream is = response.getBody()) {
                    if (is == null) {
                        throw new IOException("❌ AngelOne response body is NULL");
                    }

                    log.info("⬇️ Copying downloaded file into temporary file...");
                    long bytesCopied = Files.copy(is, tmpTarget, StandardCopyOption.REPLACE_EXISTING);
                    
                    // Validate file size
                    long fileSizeMB = bytesCopied / (1024 * 1024);
                    log.info("📊 Downloaded file size: {} MB", fileSizeMB);
                    
                    if (fileSizeMB > MAX_FILE_SIZE_MB) {
                        Files.deleteIfExists(tmpTarget);
                        throw new IOException("❌ File size exceeds maximum allowed: " + fileSizeMB + " MB");
                    }

                    log.info("🔄 Moving temp file to final location...");
                    Files.move(tmpTarget, localTarget,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);

                    log.info("✅ AngelOne Script downloaded successfully.");
                }
                return null;
            };

            // Execute download
            restTemplate.execute(
                angelOneApiUrl,
                HttpMethod.GET,
                requestCallback,
                responseExtractor);

            String msg = "✅ Saved to Resources Folder: " + localTarget.toAbsolutePath();
            log.info(msg);
            return msg;

        } catch (Exception ex) {
            log.error("❌ Error downloading file from AngelOne", ex);
            return "Error: " + ex.getMessage();
        }
    }

    /**
     * Extract instruments from JSON file and save to database using batch insert
     */
    @Transactional
    private String getAllIndexToken() throws JsonProcessingException, IOException {
        log.info("📖 Starting extraction + DB insert from {}/{}", appDataPath, INSTRUMENTS_FILENAME);

        Path inputPath = Paths.get(appDataPath, INSTRUMENTS_FILENAME);

        if (!Files.exists(inputPath)) {
            log.error("❌ Input file NOT found: {}", inputPath.toAbsolutePath());
            throw new IOException("Input file not found at: " + inputPath.toAbsolutePath());
        }

        // Validate file size
        long fileSizeBytes = Files.size(inputPath);
        long fileSizeMB = fileSizeBytes / (1024 * 1024);
        log.info("📁 Reading file: {} (Size: {} MB)", inputPath.toAbsolutePath(), fileSizeMB);

        if (fileSizeMB > MAX_FILE_SIZE_MB) {
            throw new IOException("File size exceeds maximum allowed: " + fileSizeMB + " MB");
        }

        // Clear DB
        log.info("🗑️ Clearing Indexes table...");
        indexesRepo.deleteAll();

        // Get option names for filtering
        List<String> optionNameList = niftyRepo.getAllNames();
        log.info("📋 Found {} option names for filtering", optionNameList.size());

        // Parse JSON
        JsonNode rootNode = objectMapper.readTree(inputPath.toFile());
        log.info("📄 Total records found in JSON: {}", rootNode.size());

        // Collect all indexes to save (batch insert)
        List<Indexes> indexesToSave = new ArrayList<>();
        int processedCount = 0;

        for (JsonNode node : rootNode) {
            boolean shouldInsert = shouldIncludeIndex(node, optionNameList);

            if (shouldInsert) {
                Indexes indexes = createIndexFromNode(node);
                indexesToSave.add(indexes);
                processedCount++;

                // Batch insert every BATCH_SIZE records
                if (indexesToSave.size() >= BATCH_SIZE) {
                    indexesRepo.saveAll(indexesToSave);
                    log.info("💾 Saved batch of {} records (Total: {})", indexesToSave.size(), processedCount);
                    indexesToSave.clear();
                }
            }
        }

        // Save remaining records
        if (!indexesToSave.isEmpty()) {
            indexesRepo.saveAll(indexesToSave);
            log.info("💾 Saved final batch of {} records", indexesToSave.size());
        }

        log.info("✅ Total records processed: {}, Total records saved: {}", rootNode.size(), processedCount);

        // Delete source file after processing
        log.info("🗑️ Deleting source file after processing...");
        deleteFile(inputPath);

        log.info("✅ Completed JSON extraction + DB storage.");
        return "Created " + processedCount + " records";
    }

    /**
     * Determine if an index should be included based on business rules
     */
    private boolean shouldIncludeIndex(JsonNode node, List<String> optionNameList) {
        String name = node.path("name").asText();
        String symbol = node.path("symbol").asText();
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

    /**
     * Create Indexes entity from JSON node
     */
    private Indexes createIndexFromNode(JsonNode node) {
        Indexes indexes = new Indexes();
        indexes.setName(node.path("name").asText());
        indexes.setToken(node.path("token").asText());
        indexes.setSymbol(node.path("symbol").asText());
        indexes.setExchange(node.path("exch_seg").asText());
        indexes.setExpiry(node.path("expiry").asText());
        indexes.setStrike(node.path("strike").asText());
        indexes.setLotsize(node.path("lotsize").asInt());
        return indexes;
    }

    /**
     * Move instruments file from resources to Fly volume
     */
    private String moveInstrumentsFileToFlyVolume() throws IOException {
        log.info("📦 Starting copy of {} from resources to {}/...", INSTRUMENTS_FILENAME, appDataPath);

        // Load file from resources
        ClassPathResource resource = new ClassPathResource(INSTRUMENTS_FILENAME);

        if (!resource.exists()) {
            log.error("❌ {} NOT found inside resources!", INSTRUMENTS_FILENAME);
            throw new IOException(INSTRUMENTS_FILENAME + " not found in resources!");
        }

        log.info("📁 Found resource {} inside JAR", INSTRUMENTS_FILENAME);

        // Target path
        Path target = Paths.get(appDataPath, INSTRUMENTS_FILENAME);
        log.info("📌 Target path: {}", target.toAbsolutePath());

        // Ensure directory exists
        Files.createDirectories(target.getParent());
        log.info("📂 Ensured {} folder exists.", appDataPath);

        // Copy file
        try (InputStream is = resource.getInputStream()) {
            log.info("🔄 Copying resource file to Fly volume...");
            long bytesCopied = Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("📊 Copied {} bytes", bytesCopied);
        }

        log.info("✅ Copied {} to Fly volume: {}", INSTRUMENTS_FILENAME, target.toAbsolutePath());
        return "Copied " + INSTRUMENTS_FILENAME + " from resources to: " + target.toAbsolutePath();
    }

    /**
     * Delete a file if it exists
     */
    private void deleteFile(Path path) throws IOException {
        if (Files.deleteIfExists(path)) {
            log.info("🗑️ Deleted file: {}", path.toAbsolutePath());
        } else {
            log.warn("⚠️ File not found for deletion: {}", path.toAbsolutePath());
        }
    }
}