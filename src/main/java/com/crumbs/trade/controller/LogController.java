package com.crumbs.trade.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private static final String LOG_FILE_PATH = "/app/data/server.log";

    @GetMapping("/server")
    public ResponseEntity<Resource> getServerLog() {
        File logFile = new File(LOG_FILE_PATH);
        if (!logFile.exists() || !logFile.canRead()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(null);
        }

        Resource fileResource = new FileSystemResource(logFile);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(fileResource);
    }
}
