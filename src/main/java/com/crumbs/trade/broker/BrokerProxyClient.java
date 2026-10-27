package com.crumbs.trade.utility;

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

/**
 * HTTP client that sends requests through the static-IP proxy (Tinyproxy on the
 * DigitalOcean droplet). It is scoped to this bean only; it does NOT change the
 * JVM-wide proxy settings, so other HTTP clients in the app keep connecting directly.
 *
 * Requires:
 *  - Railway variables: PROXY_HOST, PROXY_PORT, PROXY_USER, PROXY_PASS
 *  - JVM flag (Dockerfile ENV JAVA_TOOL_OPTIONS):
 *        -Djdk.http.auth.tunneling.disabledSchemes=
 *    Without it, Java will not send Basic proxy credentials for HTTPS (CONNECT) tunnels
 *    and the proxy answers 407.
 *
 * If PROXY_HOST is blank, this client connects directly (useful for local runs).
 */
@Component
public class BrokerProxyClient {

    private static final Logger logger = LoggerFactory.getLogger(BrokerProxyClient.class);

    private final HttpClient client;
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
            final String u = user == null ? "" : user;
            final char[] p = pass == null ? new char[0] : pass.toCharArray();
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    // Only answer proxy challenges, never origin-server challenges
                    if (getRequestorType() == RequestorType.PROXY) {
                        return new PasswordAuthentication(u, p);
                    }
                    return null;
                }
            });
            logger.info("BrokerProxyClient: proxy enabled -> {}:{}", this.host, port);
        } else {
            logger.warn("BrokerProxyClient: PROXY_HOST/PROXY_PORT not set, using DIRECT connection");
        }

        this.client = builder.build();
    }

    public boolean isProxyEnabled() {
        return proxyEnabled;
    }

    /** Sends a request through the proxy (or directly if no proxy is configured). */
    public HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Returns the public IP seen by the outside world. Should be the reserved IP when proxied. */
    public String checkIp() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://checkip.amazonaws.com"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("checkip returned HTTP " + resp.statusCode());
        }
        return resp.body().trim();
    }

    /** Startup self-test. Remove once you are happy with the setup. */
    @EventListener(ApplicationReadyEvent.class)
    public void proxySelfTest() {
        try {
            logger.info("Proxy egress IP: {} (proxyEnabled={})", checkIp(), proxyEnabled);
        } catch (Exception e) {
            logger.error("Proxy self-test failed: {}", e.toString(), e);
        }
    }
}