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

	/*
	 * 9 AM restart the JVM
	 * 
	 * @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata") // Works public
	 * void restartJVM() { // Trigger JVM restart in a new thread to allow HTTP
	 * response new Thread(() -> { try { // Optional: short delay for response to be
	 * sent Thread.sleep(100); } catch (InterruptedException e) {
	 * Thread.currentThread().interrupt(); } JVMRestarter.restartJVM(); }).start();
	 * }
	 */

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

	    // Store in project directory (works locally + in Fly Docker)
	    String projectDir = System.getProperty("user.dir");
	    Path localTarget = Paths.get(projectDir, "Intruments.txt");

	    try {
	        Path parent = localTarget.getParent();
	        if (parent != null && Files.notExists(parent)) {
	            Files.createDirectories(parent);
	        }

	        Path tmpTarget = parent.resolve(localTarget.getFileName().toString() + ".tmp");

	        RequestCallback requestCallback = request -> {
	            HttpHeaders headers = request.getHeaders();
	            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
	            headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
	        };

	        ResponseExtractor<Void> responseExtractor = clientHttpResponse -> {
	            HttpStatus status = HttpStatus.resolve(clientHttpResponse.getRawStatusCode());
	            if (status == null || !status.is2xxSuccessful()) {
	                throw new IOException("Remote returned non-2xx: " + clientHttpResponse.getRawStatusCode());
	            }

	            try (InputStream is = clientHttpResponse.getBody()) {
	                if (is == null)
	                    throw new IOException("Remote response body is null");

	                Files.copy(is, tmpTarget, StandardCopyOption.REPLACE_EXISTING);

	                Files.move(tmpTarget, localTarget,
	                        StandardCopyOption.REPLACE_EXISTING,
	                        StandardCopyOption.ATOMIC_MOVE);
	            }
	            return null;
	        };

	        restTemplate.execute(
	                "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json",
	                HttpMethod.GET,
	                requestCallback,
	                responseExtractor);

	        String msg = "Saved to Project Folder: " + localTarget.toAbsolutePath();
	        log.info(msg);
	      
	        return msg;

	    } catch (Exception ex) {
	        log.error("Error downloading file", ex);
	        return ex.getMessage();
	    }
	}


	/*
	 * Used to get the instrument details
	 */
	// @GetMapping("/getTokens/{includeAll}")
	public String downloadFile(@PathVariable("includeAll") boolean includeAll) throws IOException {

		String url = "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json";

		// Add browser-like headers to bypass server blocking Fly.io
		RequestCallback requestCallback = request -> {
			HttpHeaders headers = request.getHeaders();
			headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON, MediaType.ALL));
			headers.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
			headers.add("Accept", "application/json");
		};

		ResponseExtractor<Void> responseExtractor = response -> {
			Files.copy(response.getBody(), deleteFile("/Intruments.txt", false));

			if (includeAll) {
				getAllIndexToken();
			} else {
				getIndexToken();
			}

			return null;
		};

		restTemplate.execute(url, HttpMethod.GET, requestCallback, responseExtractor);
		logger.info("Downloaded");
		return "Downloaded..";
	}

	public String getIndexToken() throws JsonProcessingException, IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		deleteFile("/tokens.txt", true);

		PrintWriter pw = new PrintWriter(new FileWriter(System.getProperty("user.dir") + "/tokens.txt"));
		JsonNode rootNode = objectMapper.readTree(new File(System.getProperty("user.dir") + "/Intruments.txt"));
		List<String> inputList = new ArrayList<>();
		rootNode.forEach(node -> {

			if (node.path("name").asText().equals("NIFTY") || node.path("name").asText().equals("CRUDEOIL")
			// || node.path("name").asText().equals("NATURALGAS") ||
			// node.path("name").asText().equals("FINNIFTY")
			// || node.path("name").asText().equals("BANKNIFTY") ||
			// node.path("name").asText().equals("BANKNIFTY")
			// || node.path("name").asText().equals("MIDCPNIFTY")||
			// node.path("name").asText().equals("SENSEX")
					|| node.path("name").asText().equals("INDIA VIX") || node.path("name").asText().equals("SILVERM")) {
				inputList.add(node.toString());

			}

		});
		pw.write(inputList.toString());
		pw.flush();
		pw.close();
		deleteFile("/Intruments.txt", false);
		return "Created";
	}

	public String getAllIndexToken() throws JsonProcessingException, IOException {

	    // 0️⃣ Read from common location (LOCAL + FLY.IO)
	    Path inputPath = Paths.get("/app/data/Intruments.txt");

	    if (!Files.exists(inputPath)) {
	        return "Input file not found at: " + inputPath.toAbsolutePath();
	    }

	    ObjectMapper objectMapper = new ObjectMapper();

	    // 1️⃣ Clear DB table
	    indexesRepo.deleteAll();

	    List<String> optionNameList = niftyRepo.getAllNames();
	    List<String> inputList = new ArrayList<>();

	    // 2️⃣ Read the file
	    JsonNode rootNode = objectMapper.readTree(inputPath.toFile());

	    // 3️⃣ Process all nodes
	    rootNode.forEach(node -> {

	        if ((!node.path("name").asText().matches("[a-zA-Z ]*\\d+.*")
	                && node.path("symbol").asText().contains("-EQ")
	                && node.path("exch_seg").asText().equals("NSE"))
	                || node.path("exch_seg").asText().equals("BSE")
	                || node.path("name").asText().equals("NIFTY")
	                || node.path("name").asText().equals("CRUDEOIL")
	                || node.path("name").asText().equals("NATURALGAS")
	                || node.path("name").asText().equals("INDIA VIX")
	                || node.path("name").asText().equals("SILVERM")
	                || optionNameList.contains(node.path("name").asText())) {

	            inputList.add(node.toString());

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

	    // 4️⃣ Delete file after processing
	    deleteFile(inputPath.toString(), false);

	    return "Created";
	}


	public String moveIntrumentsFileToFlyVolume() throws IOException {

		// 1️⃣ Source file: project directory
		String projectDir = System.getProperty("user.dir");
		Path source = Paths.get(projectDir, "Intruments.txt");

		if (!Files.exists(source)) {
			return "Source file not found: " + source.toAbsolutePath();
		}

		// 2️⃣ Target file: Fly volume
		Path target = Paths.get("/app/data/Intruments.txt");

		// ensure target folder exists
		Files.createDirectories(target.getParent());

		// 3️⃣ Move / copy file
		Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

		// 4️⃣ Delete the source file after moving (optional)
		//Files.deleteIfExists(source);

		return "File moved to: " + target.toAbsolutePath();
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
