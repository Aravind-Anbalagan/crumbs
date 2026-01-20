package com.crumbs.trade.dto;

import lombok.Data;

@Data
public class BrokerAuthConfig {
    private String userId;
    private String password;
    private String apiKey;
    private String apiSecret;
    private String totpSecret;
}
