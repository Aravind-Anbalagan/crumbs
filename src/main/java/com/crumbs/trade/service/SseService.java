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

@Service
public class SseService {

    private static final Logger log = LoggerFactory.getLogger(SseService.class);
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L); // 0L = no timeout

        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.info("SSE client disconnected (completion). Total: {}", emitters.size());
        });
        emitter.onTimeout(() -> {
            emitter.complete(); // ← must explicitly complete before removing
            emitters.remove(emitter);
            log.info("SSE client timed out. Total: {}", emitters.size());
        });
        emitter.onError(e -> {
            emitter.completeWithError(e); // ← must explicitly complete before removing
            emitters.remove(emitter);
            log.info("SSE client error. Total: {}", emitters.size());
        });

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

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json));
            } catch (Exception e) {
                dead.add(emitter); // broken pipe = client gone
            }
        }
        if (!dead.isEmpty()) {
            emitters.removeAll(dead);
            log.info("Removed {} dead emitters. Total: {}", dead.size(), emitters.size());
        }
    }

    // Heartbeat every 15s — keeps connection alive AND flushes dead emitters
    @Scheduled(fixedRate = 15000)
    public void heartbeat() {
        if (emitters.isEmpty()) return;
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            emitters.removeAll(dead);
            log.info("Heartbeat removed {} dead emitters. Total: {}", dead.size(), emitters.size());
        }
    }
}