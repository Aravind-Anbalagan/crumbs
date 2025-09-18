package com.crumbs.trade.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.entity.AppKey;
import com.crumbs.trade.service.AppKeyService;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/key")
public class AppKeyController {

	private final AppKeyService keyService;

	public AppKeyController(AppKeyService keyService) {
		this.keyService = keyService;
	}

	// GET /api/keys/{id}
	@GetMapping("/{id}")
	public ResponseEntity<AppKey> getKey(@PathVariable Long id) {
		return keyService.getKey(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
	}

	// PUT /api/keys/{id}
	@PutMapping("/{id}")
	public ResponseEntity<AppKey> updateKey(@PathVariable Long id, @RequestBody AppKey keyRequest) {
		AppKey updated = keyService.updateKey(id, keyRequest.getSecret());
		return ResponseEntity.ok(updated);
	}
}
