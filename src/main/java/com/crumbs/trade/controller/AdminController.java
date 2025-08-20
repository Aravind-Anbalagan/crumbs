package com.crumbs.trade.controller;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.utility.JVMRestarter;

@RestController
@RequestMapping("/admin")
public class AdminController {

    /**
     * Returns JVM health information in a readable format
     */
    @GetMapping("/health")
    public Map<String, Object> getHealth() {
        Map<String, Object> info = new HashMap<>();

        // JVM PID
        long pid = ProcessHandle.current().pid();
        info.put("PID", pid);

        // JVM Start Time (human-readable)
        long startTime = ManagementFactory.getRuntimeMXBean().getStartTime();
        String startTimeFormatted = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(startTime));
        info.put("JVM Start Time", startTimeFormatted);

        // JVM Uptime (HH:mm:ss)
        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        info.put("Uptime", formatDuration(uptimeMillis));

        // JVM Memory in MB
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        info.put("Heap Memory Used (MB)", bytesToMB(memoryBean.getHeapMemoryUsage().getUsed()));
        info.put("Heap Memory Max (MB)", bytesToMB(memoryBean.getHeapMemoryUsage().getMax()));
        info.put("Non-Heap Memory Used (MB)", bytesToMB(memoryBean.getNonHeapMemoryUsage().getUsed()));

        // JVM Threads
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        info.put("Thread Count", threadBean.getThreadCount());
        info.put("Daemon Thread Count", threadBean.getDaemonThreadCount());
        info.put("Peak Thread Count", threadBean.getPeakThreadCount());

        return info;
    }

    /**
     * Triggers a full JVM restart
     */
    @GetMapping("/restart-jvm")
    public String restartJVM() {
        new Thread(() -> JVMRestarter.restartJVM()).start();
        return "JVM restart triggered!";
    }

    private long bytesToMB(long bytes) {
        return bytes / (1024 * 1024);
    }

    private String formatDuration(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
