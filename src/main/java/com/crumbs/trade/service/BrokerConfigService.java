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

        return config;
    }
}
