package com.crumbs.trade.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.BrokerAuthConfig;
import com.crumbs.trade.entity.Brokers;
import com.crumbs.trade.repo.BrokersRepo;


@Service
public class BrokerConfigService {

    @Autowired
    private BrokersRepo brokersRepository;

    public BrokerAuthConfig getFlatTradeConfig() {
        Brokers broker = brokersRepository.findByBrokername("FLATTRADE")
                .orElseThrow(() -> new RuntimeException("FlatTrade broker config not found"));

        BrokerAuthConfig config = new BrokerAuthConfig();
        config.setUserId(broker.getUsername());
        config.setPassword(broker.getPassword());
        config.setApiKey(broker.getApikey());
        config.setApiSecret(broker.getApisecret());
        config.setTotpSecret(broker.getTotpsecret());
        
        // Pass the code stored in DB to the config DTO
        config.setRequestCode(broker.getRequestcode());

        return config;
    }

    /**
     * Updates the request code in the DB. 
     * You can call this from a Controller or run it manually.
     */
    public void updateRequestCode(String code) {
        Brokers broker = brokersRepository.findByBrokername("FLATTRADE")
                .orElseThrow(() -> new RuntimeException("FlatTrade broker not found"));
        
        broker.setRequestcode(code);
        brokersRepository.save(broker);
    }

    /**
     * Clears the code after a successful exchange 
     * to prevent using an expired/used code.
     */
    public void clearRequestCode() {
        updateRequestCode(null);
    }
}