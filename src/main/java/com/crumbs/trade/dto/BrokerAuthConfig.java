package com.crumbs.trade.dto;

import java.time.Instant;

import lombok.Data;

@Data
public class BrokerAuthConfig {
    private String userId;
    private String password;
    private String apiKey;
    private String apiSecret;
    private String totpSecret;
    private String requestCode;
    private String apiToken;
    private Instant tokenDate;
}
