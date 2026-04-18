package com.crumbs.trade.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crumbs.trade.dto.BrokerAuthConfig;
import com.crumbs.trade.entity.Brokers;
import com.crumbs.trade.repo.BrokersRepo;

import java.time.Instant;

@Service
public class BrokerConfigService {

    @Autowired
    private BrokersRepo brokersRepository;

    /**
     * Retrieves the complete configuration including the saved Token and its Timestamp.
     */
    public BrokerAuthConfig getFlatTradeConfig() {
        Brokers broker = brokersRepository.findByBrokername("FLATTRADE")
                .orElseThrow(() -> new RuntimeException("FlatTrade broker config not found"));

        BrokerAuthConfig config = new BrokerAuthConfig();
        config.setUserId(broker.getUsername());
        config.setPassword(broker.getPassword());
        config.setApiKey(broker.getApikey());
        config.setApiSecret(broker.getApisecret());
        config.setTotpSecret(broker.getTotpsecret());
        
        // request_code: used for exchange
        config.setRequestCode(broker.getRequestcode());

        // api_token: the JKey used for trading
        config.setApiToken(broker.getApitoken());
        
        // Assuming your entity has a field for the timestamp (e.g., Instant or LocalDateTime)
        config.setTokenDate(broker.getTokentimestamp());

        return config;
    }

    /**
     * Updates the request code (Step 1 from browser).
     */
    @Transactional
    public void updateRequestCode(String code) {
        Brokers broker = brokersRepository.findByBrokername("FLATTRADE")
                .orElseThrow(() -> new RuntimeException("FlatTrade broker not found"));
        
        broker.setRequestcode(code);
        brokersRepository.save(broker);
    }

    /**
     * Saves the final generated Token (JKey) and the current time to the DB.
     * Call this after a successful Step 2 (Token Exchange).
     */
    @Transactional
    public void updateApiToken(String token) {
        Brokers broker = brokersRepository.findByBrokername("FLATTRADE")
                .orElseThrow(() -> new RuntimeException("FlatTrade broker not found"));
        
        broker.setApitoken(token);
        broker.setTokentimestamp(Instant.now()); // Marks when the token was generated
        brokersRepository.save(broker);
    }

    /**
     * Clears the request_code after it has been exchanged for a token.
     */
    public void clearRequestCode() {
        updateRequestCode(null);
    }
}