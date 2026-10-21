package com.sdigital.platform.user.keycloak;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link KeycloakAdminConfig} actually applies the configured read
 * timeout to the {@link RestClient} used by {@link KeycloakAdminClient} (M2).
 *
 * <p>The test starts a local HTTP server that delays its response and shows that a
 * request through the configured client aborts after roughly the read timeout,
 * whereas the default client would keep waiting. No real Keycloak is contacted.</p>
 */
class KeycloakAdminConfigTest {

    @Test
    void readTimeoutIsAppliedToTheRestClient() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean responded = new AtomicBoolean(false);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            responded.set(true);
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            KeycloakAdminProperties properties = new KeycloakAdminProperties();
            properties.setServerUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setRealm("s-digital");
            properties.setClientId("backend-admin");
            properties.setClientSecret("s3cr3t");
            properties.setConnectTimeout(Duration.ofMillis(500));
            properties.setReadTimeout(Duration.ofMillis(300));

            KeycloakAdminClient client =
                    new KeycloakAdminConfig().keycloakAdminClient(properties, RestClient.builder());

            long start = System.nanoTime();
            assertThatThrownBy(() -> client.findUserId("sub-slow"))
                    .isInstanceOf(RuntimeException.class);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMillis)
                    .as("request should abort close to the configured 300ms read timeout, not the 3s server delay")
                    .isLessThan(2500);
            assertThat(responded).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
