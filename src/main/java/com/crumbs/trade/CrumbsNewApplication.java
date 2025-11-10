package com.crumbs.trade;

import java.time.Duration;
import java.util.TimeZone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;

@EnableScheduling
@EnableJpaAuditing
@EntityScan("com.crumbs.trade.entity")
@SpringBootApplication
public class CrumbsNewApplication {

    private static final Logger log = LoggerFactory.getLogger(CrumbsNewApplication.class);

    @Autowired
    private AngelOne angelOne;

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication.run(CrumbsNewApplication.class, args);
    }

    @Bean
    public RestTemplate getRestTemplate() {
        return new RestTemplateBuilder()
                .setReadTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Bean
    public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(10);
        scheduler.setThreadNamePrefix("crumbs-scheduler-");
        scheduler.setErrorHandler(t -> log.error("Scheduler error", t));
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    public SmartConnect getSmartConnect() {
        return angelOne.signIn();
    }
}