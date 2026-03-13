package com.crumbs.trade.config;



import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name  = "scheduling.enabled",
    havingValue = "true",
    matchIfMissing = false  // ← if property missing, scheduling OFF
)
public class SchedulerConfig {
    // nothing needed here
}
