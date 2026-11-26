package com.crumbs.trade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

@Service
public class SseService {

    private static final Logger log = LoggerFactory.getLogger(SseService.class);
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L);

        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.info("SSE client disconnected (completion). Total: {}", emitters.size());
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            completeQuietly(emitter);
            log.info("SSE client timed out. Total: {}", emitters.size());
        });
        emitter.onError(e -> {
            emitters.remove(emitter);
            log.info("SSE client error ({}). Total: {}", e.getMessage(), emitters.size());
        });

        try {
            // Flushes HTTP headers immediately so EventSource.onopen fires right away
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception e) {
            completeQuietly(emitter);
            return emitter;
        }

        emitters.add(emitter);
        log.info("SSE client connected. Total: {}", emitters.size());
        return emitter;
    }

    public void broadcast(String eventName, Object payload) {
        if (emitters.isEmpty()) return;

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("SSE serialize error", e);
            return;
        }

        sendToAll(() -> SseEmitter.event().name(eventName).data(json), "Broadcast");
    }

    // Heartbeat every 15s — keeps HTTP/2 proxy alive and clears dead clients
    @Scheduled(fixedRate = 15000)
    public void heartbeat() {
        if (emitters.isEmpty()) return;
        sendToAll(() -> SseEmitter.event().name("heartbeat").data("ping"), "Heartbeat");
    }

    private void sendToAll(Supplier<SseEmitter.SseEventBuilder> eventSupplier, String source) {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(eventSupplier.get());
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            emitters.removeAll(dead);
            dead.forEach(this::completeQuietly);
            log.info("{} removed {} dead emitters. Total: {}", source, dead.size(), emitters.size());
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }
}