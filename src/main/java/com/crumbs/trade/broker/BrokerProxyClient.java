package com.crumbs.trade.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Component
public class BrokerProxyClient {

    private static final Logger logger = LoggerFactory.getLogger(BrokerProxyClient.class);

    static {
        // Allow Basic auth on HTTPS (CONNECT) proxy tunnels
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
    }

    private final HttpClient client;
    /** Direct connection (no proxy): login and market data. */
    private final HttpClient directClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final boolean proxyEnabled;
    private final String host;
    private final int port;

    /** "Basic xxx" or null. Sent as Proxy-Authorization instead of using an Authenticator. */
    private final String proxyAuthHeader;

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

        String authHeader = null;

        if (proxyEnabled) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(this.host, port)));

            // NOTE: no builder.authenticator(...) here. Attaching a proxy Authenticator
            // can cause the JDK to drop the request's own Authorization header
            // (JDK-8326949), which makes Angel answer "Token missing / AG8003".
            // Proxy credentials are sent as a Proxy-Authorization header instead.
            if (user != null && !user.trim().isEmpty()) {
                String creds = user.trim() + ":" + (pass == null ? "" : pass);
                authHeader = "Basic " + Base64.getEncoder()
                        .encodeToString(creds.getBytes(StandardCharsets.UTF_8));
            }

            logger.info("BrokerProxyClient: proxy enabled -> {}:{} (auth={})",
                    this.host, port, authHeader != null);
        } else {
            logger.warn("BrokerProxyClient: PROXY_HOST/PROXY_PORT not set, using DIRECT connection");
        }

        this.proxyAuthHeader = authHeader;
        this.client = builder.build();
    }

    public boolean isProxyEnabled() {
        return proxyEnabled;
    }

    private HttpRequest withProxyAuth(HttpRequest request) {
        if (!proxyEnabled || proxyAuthHeader == null) {
            return request;
        }
        // Copy the request (keeps all headers incl. Authorization) and add Proxy-Authorization
        return HttpRequest.newBuilder(request, (k, v) -> true)
                .header("Proxy-Authorization", proxyAuthHeader)
                .build();
    }

    /** Goes out through the static-IP proxy. Use for anything Angel IP-checks. */
    public HttpResponse<String> sendOrder(HttpRequest request) throws Exception {
        return client.send(withProxyAuth(request), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> sendDirect(HttpRequest request) throws Exception {
        return directClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public String checkIp() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://checkip.amazonaws.com"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(withProxyAuth(req), HttpResponse.BodyHandlers.ofString());
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

    public String getHost() { return host; }
    public int getPort() { return port; }
}