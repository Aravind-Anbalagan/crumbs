package com.crumbs.trade.service;

import com.crumbs.trade.entity.NIFTY500;
import com.crumbs.trade.repo.Nifty500Repo;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

@Service
public class SectorUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(SectorUpdateService.class);

    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 1500; // base delay per request
    private final ExecutorService executor = Executors.newFixedThreadPool(5); // 5 threads

    @Autowired
    private Nifty500Repo nifty500Repo;

    // Scheduled nightly job (2 AM)
    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledUpdate() throws InterruptedException {
        updateSectorsAndIndustry();
    }

    // Manual trigger
    public void updateSectorsAndIndustry() throws InterruptedException {
        // Fetch only stocks where sector or industry is null or "Unknown"
    	List<NIFTY500> stocks = new ArrayList<>(
    	        nifty500Repo.findAll().stream()
    	            .filter(s -> s.getSector() == null || s.getSector().equalsIgnoreCase("Unknown")
    	                    || s.getIndustry() == null || s.getIndustry().equalsIgnoreCase("Unknown"))
    	            .toList()
    	);


        if (stocks.isEmpty()) {
            logger.info("All stocks already have valid sector and industry. Nothing to update.");
            return;
        }

        // Randomize the order to reduce blocking
        Collections.shuffle(stocks);

        int batchSize = 50;
        int totalStocks = stocks.size();

        for (int i = 0; i < totalStocks; i += batchSize) {
            int end = Math.min(i + batchSize, totalStocks);
            List<NIFTY500> batch = stocks.subList(i, end);

            List<Future<?>> futures = new CopyOnWriteArrayList<>();

            for (NIFTY500 stock : batch) {
                futures.add(executor.submit(() -> {
                    try {
                        String symbol = stock.getName().trim().toUpperCase() + ".NS";
                        SectorIndustry si = fetchSectorAndIndustryWithRetry(symbol);

                        stock.setSector(si.sector);
                        stock.setIndustry(si.industry);
                        logger.info("Updated " + symbol + " -> Sector: " + si.sector + ", Industry: " + si.industry);

                        // Random delay 1.5–2.5s
                        Thread.sleep(BASE_DELAY_MS + (long) (Math.random() * 1000));

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            // Wait for batch to finish
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    logger.error("Task failed: " + e.getMessage());
                }
            }

            // Save batch
            nifty500Repo.saveAll(batch);
            logger.info("Batch " + (i / batchSize + 1) + " saved successfully.");
        }

        logger.info("✅ All sectors and industries updated successfully!");
    }

    // Retry wrapper with exponential backoff
    private SectorIndustry fetchSectorAndIndustryWithRetry(String symbol) {
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                return fetchSectorAndIndustry(symbol);
            } catch (HttpStatusException e) {
                if (e.getStatusCode() == 404) {
                    logger.warn("Stock " + symbol + " profile not found (404). Returning Unknown.");
                    return new SectorIndustry("Unknown", "Unknown");
                }
            } catch (Exception e) {
                logger.error("Retry " + (i + 1) + " for " + symbol + ": " + e.getMessage());
            }

            // Exponential backoff with jitter
            try {
                long waitTime = (2000L * (long) Math.pow(2, i)) + (long) (Math.random() * 1000);
                logger.info("Waiting " + waitTime + "ms before retrying " + symbol);
                Thread.sleep(waitTime);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return new SectorIndustry("Unknown", "Unknown");
    }

    // Scraper for both Sector and Industry
    private SectorIndustry fetchSectorAndIndustry(String symbol) throws Exception {
        String url = "https://finance.yahoo.com/quote/" + symbol + "/profile";
        try {
            return scrapeSectorIndustryFromUrl(url);
        } catch (HttpStatusException e) {
            // Retry without .NS suffix if 404
            if (e.getStatusCode() == 404 && symbol.endsWith(".NS")) {
                url = "https://finance.yahoo.com/quote/" + symbol.replace(".NS", "") + "/profile";
                return scrapeSectorIndustryFromUrl(url);
            } else {
                throw e;
            }
        }
    }

    private SectorIndustry scrapeSectorIndustryFromUrl(String url) throws Exception {
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(20000)
                .get();

        // Get all links with the class subtle-link.fin-size-large.yf-iqvwrv
        Elements elements = doc.select("a.subtle-link.fin-size-large.yf-iqvwrv");

        String sector = "Unknown";
        String industry = "Unknown";

        if (!elements.isEmpty()) {
            sector = elements.get(0).text().trim();       // first link → Sector
            if (elements.size() > 1) {
                industry = elements.get(1).text().trim(); // second link → Industry
            }
        }

        // Check bot protection
        if ((sector.equals("Unknown") && industry.equals("Unknown"))
                && doc.body().text().contains("Sign in to Yahoo Finance")) {
            throw new Exception("Page blocked by Yahoo, retry later: " + url);
        }

        return new SectorIndustry(sector, industry);
    }

    // Helper class
    private static class SectorIndustry {
        String sector;
        String industry;
        SectorIndustry(String sector, String industry) {
            this.sector = sector;
            this.industry = industry;
        }
    }
}
