package com.crumbs.trade.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class BrokerProxyClient {

    private static final Logger logger = LoggerFactory.getLogger(BrokerProxyClient.class);

    private final HttpClient client;
    /** Direct connection (Railway's own IP): login and market data. */
    private final HttpClient directClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final boolean proxyEnabled;
    private final String host;
    private final int port;

    public BrokerProxyClient(
            @Value("${PROXY_HOST:}") String host,
            @Value("${PROXY_PORT:0}") int port,
            @Value("${PROXY_USER:}") String user,
            @Value("${PROXY_PASS:}") String pass) {

        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.proxyEnabled = !this.host.isEmpty() && port > 0;

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1);

        if (proxyEnabled) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(this.host, port)));

            // 🟢 CRITICAL FIX: Only attach Authenticator if a proxy username is provided
            // This bypasses Java Bug JDK-8326949 which silently deletes the Authorization header
            if (user != null && !user.trim().isEmpty()) {
                final String u = user.trim();
                final char[] p = pass == null ? new char[0] : pass.toCharArray();
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() == RequestorType.PROXY) {
                            return new PasswordAuthentication(u, p);
                        }
                        return null;
                    }
                });
            }

            logger.info("BrokerProxyClient: proxy enabled -> {}:{}", this.host, port);
        } else {
            logger.warn("BrokerProxyClient: PROXY_HOST/PROXY_PORT not set, using DIRECT connection");
        }

        this.client = builder.build();
    }

    public boolean isProxyEnabled() {
        return proxyEnabled;
    }

    public HttpResponse<String> sendOrder(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> sendDirect(HttpRequest request) throws Exception {
        return directClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public String checkIp() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://checkip.amazonaws.com"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("checkip returned HTTP " + resp.statusCode());
        }
        return resp.body().trim();
    }

    @Value("${PROXY_SELFTEST:false}")
    private boolean selfTestEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void proxySelfTest() {
        if (!selfTestEnabled) {
            return;
        }
        try {
            logger.info("Proxy egress IP: {} (proxyEnabled={})", checkIp(), proxyEnabled);
        } catch (Exception e) {
            logger.error("Proxy self-test failed: {}", e.toString(), e);
        }
    }
}