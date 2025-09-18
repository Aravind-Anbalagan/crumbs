package com.crumbs.trade.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.AppKey;
import com.crumbs.trade.repo.AppKeyRepo;

@Service
public class AppKeyService {
    private final AppKeyRepo appKeyRepo;

    public AppKeyService(AppKeyRepo appKeyRepo) {
        this.appKeyRepo = appKeyRepo;
    }

    public Optional<AppKey> getKey(Long id) {
        return appKeyRepo.findById(id);
    }

    public AppKey updateKey(Long id, String newSecret) {
    	AppKey key = appKeyRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Key not found"));
        key.setSecret(newSecret);
        return appKeyRepo.save(key);
    }
}
