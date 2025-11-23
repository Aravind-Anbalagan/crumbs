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

    public boolean validateKey(Long id, String secret) {
        return appKeyRepo.findById(id)
                .map(key -> key.getSecret().equals(secret))
                .orElse(false);   // ID not found = invalid
    }
}

