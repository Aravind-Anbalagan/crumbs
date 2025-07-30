package com.crumbs.trade.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

@Configuration
@OpenAPIDefinition(
    info = @Info(title = "Crumbs API", version = "v1"),
    servers = {
        @Server(url = "") // placeholder, will be updated
    }
)
public class OpenApiConfig {
    @Value("${server.hostname:localhost}")
    private String hostname;

    @PostConstruct
    public void printSwaggerBaseUrl() {
        String swaggerUrl;

        if ("localhost".equalsIgnoreCase(hostname) || hostname.contains("127.0.0.1")) {
            swaggerUrl = "http://localhost:8080";
        } else {
            swaggerUrl = "https://crumbs.fly.dev";
        }

        System.out.println("🌐 Swagger base URL: " + swaggerUrl);
        // This will not change the annotation but helps log what it's using.
    }
}
