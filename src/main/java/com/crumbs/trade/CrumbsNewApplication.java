package com.crumbs.trade;

import java.io.IOException;
import java.time.Duration;
import java.util.TimeZone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestTemplate;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.controller.CommonController;
import com.crumbs.trade.service.DownloadService;
@EnableScheduling
@EntityScan("com.crumbs.trade.entity")
@SpringBootApplication
public class CrumbsNewApplication {
	private AngelOne angelOne;
	
	private DownloadService downloadService;

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
		SpringApplication.run(CrumbsNewApplication.class, args);
	}

	@Bean
	public RestTemplate getRestTemplate() {
		RestTemplate restTemplate = new RestTemplateBuilder().setReadTimeout(Duration.ofSeconds(20)).build();
		return restTemplate;
	}

	@Bean
	public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
		return new PropertySourcesPlaceholderConfigurer();
	}

	@Bean
	public TaskScheduler taskScheduler() {
	    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
	    scheduler.setPoolSize(10); // ✅ Try 10 or even 4
	    scheduler.setThreadNamePrefix("ThreadPoolTaskScheduler-");
	    scheduler.initialize(); // Good practice
	    return scheduler;
	}

	@Bean
	public SmartConnect getSmartConnect() {
		return angelOne.signIn();

	}

	
}
