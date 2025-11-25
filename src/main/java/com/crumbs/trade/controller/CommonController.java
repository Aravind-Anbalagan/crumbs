package com.crumbs.trade.controller;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import com.crumbs.trade.utility.JVMRestarter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

@RestController
@RequestMapping(value = "/common")
public class CommonController {
	Logger logger = LoggerFactory.getLogger(CommonController.class);

	@Autowired
	NiftyRepo niftyRepo;

	@Autowired
	RestTemplate restTemplate;

	@Autowired
	IndexesRepo indexesRepo;

	@Autowired
	AngelOneService angelOneService;

	private static final Logger log = LoggerFactory.getLogger(CommonController.class);


	// DeActivate the Strategy
	@GetMapping(value = "/clear")
	public String deleteOrders() throws InterruptedException, URISyntaxException, IOException, SmartAPIException {
		logger.info("Delete All Data");
		angelOneService.deleteOrders();
		return "Completed";

	}

	/*
	 * 9.10 AM clear DB
	 */
	@Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
	public void clear() throws InterruptedException, URISyntaxException, IOException, SmartAPIException {
		logger.info("Delete All Data");
		angelOneService.deleteOrders();
	}


	// Execute every FRI at 11 PM
	@Scheduled(cron = "0 0 23 * * FRI", zone = "Asia/Kolkata")
	public String fetchTokensScheduled() throws IOException {
		return getAllIndexToken();
	}

    /*
     *  1. Download the file from the Angelone
     *  2. Store in the DB
     */
	@PostMapping("/download-script-locally")
	public String downloadScriptsLocal() throws JsonProcessingException, IOException {
		  downloadScriptFromAngelOne();
		  moveIntrumentsFileToFlyVolume();
		  getAllIndexToken();
		  return "Completed";
	}

	@PostMapping("/extract-script-OnFly")
	public void extractScript() throws JsonProcessingException, IOException {
		// Copy the file
		moveIntrumentsFileToFlyVolume();
		// Extract the file and store in DB
		getAllIndexToken();
	}
	
	public String downloadScriptFromAngelOne() {

	    log.info("⬇️ Starting download of AngelOne Script File...");

	    // Write to src/main/resources (only on local machine)
	    Path localTarget = Paths.get("src/main/resources/Intruments.txt");
	    log.info("📁 Local resource output path: {}", localTarget.toAbsolutePath());

	    try {
	        Path parent = localTarget.getParent();
	        if (parent != null && Files.notExists(parent)) {
	            Files.createDirectories(parent);
	            log.info("📂 Created directory: {}", parent.toAbsolutePath());
	        }

	        Path tmpTarget = parent.resolve("Intruments.txt.tmp");
	        log.info("📝 Temporary write target: {}", tmpTarget.toAbsolutePath());

	        RequestCallback requestCallback = request -> {
	            HttpHeaders headers = request.getHeaders();
	            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0");
	            headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
	            log.info("🌐 AngelOne request headers set.");
	        };

	        ResponseExtractor<Void> responseExtractor = response -> {
	            HttpStatus status = HttpStatus.resolve(response.getRawStatusCode());
	            log.info("🌐 AngelOne API HTTP Status: {}", status);

	            if (status == null || !status.is2xxSuccessful()) {
	                throw new IOException("❌ AngelOne returned non-2xx: " + response.getRawStatusCode());
	            }

	            try (InputStream is = response.getBody()) {
	                if (is == null)
	                    throw new IOException("❌ AngelOne response body is NULL");

	                log.info("⬇️ Copying downloaded file into temporary file...");
	                Files.copy(is, tmpTarget, StandardCopyOption.REPLACE_EXISTING);

	                log.info("🔄 Moving temp file to final location...");
	                Files.move(tmpTarget, localTarget,
	                        StandardCopyOption.REPLACE_EXISTING,
	                        StandardCopyOption.ATOMIC_MOVE);

	                log.info("✅ AngelOne Script downloaded successfully.");
	            }
	            return null;
	        };

	        restTemplate.execute(
	            "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json",
	            HttpMethod.GET,
	            requestCallback,
	            responseExtractor);

	        String msg = "✅ Saved to Resources Folder: " + localTarget.toAbsolutePath();
	        log.info(msg);
	        return msg;

	    } catch (Exception ex) {
	        log.error("❌ Error downloading file from AngelOne", ex);
	        return ex.getMessage();
	    }
	}

	

	public String getAllIndexToken() throws JsonProcessingException, IOException {

	    log.info("📖 Starting extraction + DB insert from /app/data/Intruments.txt");

	    Path inputPath = Paths.get("/app/data/Intruments.txt");

	    if (!Files.exists(inputPath)) {
	        log.error("❌ Input file NOT found: {}", inputPath.toAbsolutePath());
	        return "Input file not found at: " + inputPath.toAbsolutePath();
	    }

	    log.info("📁 Reading file: {}", inputPath.toAbsolutePath());

	    ObjectMapper objectMapper = new ObjectMapper();

	    // Clear DB
	    log.info("🗑️ Clearing Indexes table...");
	    indexesRepo.deleteAll();

	    List<String> optionNameList = niftyRepo.getAllNames();
	    List<String> inputList = new ArrayList<>();

	    JsonNode rootNode = objectMapper.readTree(inputPath.toFile());
	    log.info("📄 Total records found in JSON: {}", rootNode.size());

	    rootNode.forEach(node -> {
	        boolean shouldInsert =
	                ((!node.path("name").asText().matches("[a-zA-Z ]*\\d+.*")
	                        && node.path("symbol").asText().contains("-EQ")
	                        && node.path("exch_seg").asText().equals("NSE"))
	                        || node.path("exch_seg").asText().equals("BSE")
	                        || node.path("name").asText().equals("NIFTY")
	                        || node.path("name").asText().equals("CRUDEOIL")
	                        || node.path("name").asText().equals("NATURALGAS")
	                        || node.path("name").asText().equals("INDIA VIX")
	                        || node.path("name").asText().equals("SILVERM")
	                        || optionNameList.contains(node.path("name").asText()));

	        if (shouldInsert) {
	            //log.debug("➕ Adding: {}", node.path("name").asText());

	            Indexes indexes = new Indexes();
	            indexes.setName(node.path("name").asText());
	            indexes.setToken(node.path("token").asText());
	            indexes.setSymbol(node.path("symbol").asText());
	            indexes.setExchange(node.path("exch_seg").asText());
	            indexes.setExpiry(node.path("expiry").asText());
	            indexes.setStrike(node.path("strike").asText());
	            indexes.setLotsize(node.path("lotsize").asInt());

	            indexesRepo.save(indexes);
	        }
	    });

	    log.info("🗑️ Deleting source file after processing...");
	    deleteFile(inputPath.toString(), false);

	    log.info("✅ Completed JSON extraction + DB storage.");
	    return "Created";
	}



	public String moveIntrumentsFileToFlyVolume() throws IOException {

	    log.info("📦 Starting copy of Intruments.txt from resources to /app/data/...");

	    // Load file from resources
	    ClassPathResource resource = new ClassPathResource("Intruments.txt");

	    if (!resource.exists()) {
	        log.error("❌ Intruments.txt NOT found inside resources!");
	        return "Intruments.txt not found in resources!";
	    }

	    log.info("📁 Found resource Intruments.txt inside JAR");

	    // Target path
	    Path target = Paths.get("/app/data/Intruments.txt");
	    log.info("📌 Target path: {}", target.toAbsolutePath());

	    Files.createDirectories(target.getParent());
	    log.info("📂 Ensured /app/data folder exists.");

	    try (InputStream is = resource.getInputStream()) {
	        log.info("🔄 Copying resource file to Fly volume...");
	        Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
	    }

	    log.info("✅ Copied Intruments.txt to Fly volume: {}", target.toAbsolutePath());
	    return "Copied Intruments.txt from resources to: " + target.toAbsolutePath();
	}



	public Path deleteFile(String fileName, boolean isCreate) throws IOException {
		Path path = Paths.get(System.getProperty("user.dir") + fileName);
		Files.deleteIfExists(path);

		if (isCreate) {
			Files.createFile(path);
		}
		return path;
	}

}
