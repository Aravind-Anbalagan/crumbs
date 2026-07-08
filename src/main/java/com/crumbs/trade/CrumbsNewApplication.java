package com.crumbs.trade;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.TimeZone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestTemplate;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;

@EnableJpaAuditing
@EntityScan("com.crumbs.trade.entity")
@SpringBootApplication(exclude = { SpringApplicationAdminJmxAutoConfiguration.class })
public class CrumbsNewApplication {

    private static final Logger log = LoggerFactory.getLogger(CrumbsNewApplication.class);

    @Autowired
    private AngelOne angelOne;

    // Reads PROXY_HOST / PROXY_PORT from Railway env variables
    // Falls back to empty string if not set (local dev)
    @Value("${PROXY_HOST:}")
    private String proxyHost;

    @Value("${PROXY_PORT:0}")
    private int proxyPort;

    public static void main(String[] args) {
    	
    	 try {
    		 TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    	        SpringApplication.run(CrumbsNewApplication.class, args);
         } catch (Exception e) {
             e.printStackTrace(); // goes straight to stderr
             throw e;
         }
       
    }

    /**
     * RestTemplate — routes through proxy if PROXY_HOST is set.
     * This ensures ALL outbound HTTP calls (Telegram, Angel One REST,
     * Samco) go through the whitelisted IP, not Railway's raw IP.
     */
    @Bean
    public RestTemplate getRestTemplate() {
        if (proxyHost != null && !proxyHost.isEmpty()) {
            log.info("RestTemplate using proxy: {}:{}", proxyHost, proxyPort);
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setProxy(new Proxy(
                Proxy.Type.HTTP,
                new InetSocketAddress(proxyHost, proxyPort)
            ));
            return new RestTemplateBuilder()
                    .setConnectTimeout(Duration.ofSeconds(5))  // 🛑 Abort fast if proxy is unreachable
                    .setReadTimeout(Duration.ofSeconds(15))    // 🛑 Max wait for broker payload
                    .requestFactory(() -> factory)
                    .build();
        }
        log.info("RestTemplate using direct connection (no proxy)");
        return new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * SmartConnect — @Lazy so Angel One login happens on first use,
     * not at startup. Prevents crash if Angel One is temporarily down
     * at deploy time.
     */
    @Bean
    @Lazy
    public SmartConnect getSmartConnect() {
        return angelOne.signIn();
    }

    @Bean
    public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(10);
        scheduler.setThreadNamePrefix("crumbs-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setErrorHandler(t ->
            log.error("Uncaught scheduler exception", t)
        );
        scheduler.setRejectedExecutionHandler((r, e) ->
            log.error("Scheduler rejected task: {}", r)
        );
        scheduler.initialize();
        return scheduler;
    }
}