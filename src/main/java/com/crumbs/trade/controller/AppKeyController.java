package com.crumbs.trade.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.entity.AppKey;
import com.crumbs.trade.service.AppKeyService;

@RestController
@RequestMapping("/key")
@CrossOrigin(origins = "*")
public class AppKeyController {

    private final AppKeyService keyService;

    public AppKeyController(AppKeyService keyService) {
        this.keyService = keyService;
    }

    @PostMapping("/{id}/validate")
    public ResponseEntity<Boolean> validate(
            @PathVariable Long id,
            @RequestBody AppKey request
    ) {
        boolean isValid = keyService.validateKey(id, request.getSecret());
        return ResponseEntity.ok(isValid);
    }
}

