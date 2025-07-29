package com.crumbs.trade.service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.repo.IndexesRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;



@Component
public class DownloadService {
	
Logger logger = LoggerFactory.getLogger(DownloadService.class);
	
	

	@Autowired
	RestTemplate restTemplate;
	
    @Autowired
    IndexesRepo indexesRepo;
    
	public String downloadFile(@PathVariable("includeAll") boolean includeAll) throws IOException {
		String url = "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json";
		// Optional Accept header
		RequestCallback requestCallback = request -> request.getHeaders()
				.setAccept(Arrays.asList(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));

		// Streams the response instead of loading it all in memory
		ResponseExtractor<Void> responseExtractor = response -> {
			// Here you can write the inputstream to a file or any other place

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
					//|| node.path("name").asText().equals("NATURALGAS") || node.path("name").asText().equals("FINNIFTY")
					//|| node.path("name").asText().equals("BANKNIFTY") || node.path("name").asText().equals("BANKNIFTY")
					//|| node.path("name").asText().equals("MIDCPNIFTY")|| node.path("name").asText().equals("SENSEX")
					|| node.path("name").asText().equals("INDIA VIX") || node.path("name").asText().equals("GOLD")) {
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
		ObjectMapper objectMapper = new ObjectMapper();
		deleteFile("/alltokens.txt", true);
		indexesRepo.deleteAll();
		PrintWriter pw = new PrintWriter(new FileWriter(System.getProperty("user.dir") + "/alltokens.txt"));
		JsonNode rootNode = objectMapper.readTree(new File(System.getProperty("user.dir") + "/Intruments.txt"));
		List<String> inputList = new ArrayList<>();
		rootNode.forEach(node -> {

			
			if (!node.path("name").asText().matches("[a-zA-Z ]*\\d+.*") 
				&& node.path("symbol").asText().contains("-EQ")
				&& node.path("exch_seg").asText().equals("NSE") ||
				node.path("exch_seg").asText().equals("BSE") ||(node.path("name").asText().equals("NIFTY") || node.path("name").asText().equals("CRUDEOIL")
						|| node.path("name").asText().equals("NATURALGAS")
						//|| node.path("name").asText().equals("FINNIFTY")
						//|| node.path("name").asText().equals("BANKNIFTY") || node.path("name").asText().equals("BANKNIFTY")
						//|| node.path("name").asText().equals("MIDCPNIFTY")|| node.path("name").asText().equals("SENSEX")
						|| node.path("name").asText().equals("INDIA VIX") || node.path("name").asText().equals("GOLD")) ) {
				inputList.add(node.toString());

				//Save Index Values
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
		pw.write(inputList.toString());
		pw.flush();
		pw.close();
		deleteFile("/Intruments.txt", false);
		return "Created";
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
