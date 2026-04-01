package com.crumbs.trade.utility;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.crumbs.trade.broker.Samco;

@Component
public class SamcoSessionManager {

    @Autowired
    private Samco samco;

    private String sessionToken;
    private LocalDate sessionDate;

    /**
     * Returns cached session if still valid for today.
     * Generates a new one only if it's a new trading day.
     */
    public String getSession() {
        if (sessionToken == null || !LocalDate.now().equals(sessionDate)) {
            sessionToken = samco.getSamcoSession();
            sessionDate  = LocalDate.now();
        }
        return sessionToken;
    }

    /** Call this if a session-expired error is received mid-day. */
    public void invalidate() {
        sessionToken = null;
        sessionDate  = null;
    }
}