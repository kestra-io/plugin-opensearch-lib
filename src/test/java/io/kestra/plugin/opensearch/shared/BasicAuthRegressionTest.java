package io.kestra.plugin.opensearch.shared;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.IndexRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Regression test for the {@code httpAsyncClientBuilder} fix: the OSS implementation used to call
 * {@code HttpAsyncClientBuilder.create()} and discard the builder handed in by
 * {@code HttpClientConfigCallback}, silently dropping any basic-auth credentials configured on
 * {@link OpensearchConnection}. This test proves the credentials actually reach the request.
 */
@KestraTest
class BasicAuthRegressionTest {
    private static final String USERNAME = "opensearch";
    private static final String PASSWORD = "changeme";
    private static final String EXPECTED_AUTHORIZATION = "Basic " + Base64.getEncoder()
        .encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    private static final String EXPLICIT_AUTHORIZATION_HEADER = "Token XYZ";

    private static HttpServer server;
    private static String host;
    private static final AtomicReference<String> LAST_AUTHORIZATION_HEADER = new AtomicReference<>();

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void beforeAll() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange ->
        {
            var authorization = exchange.getRequestHeaders().getFirst("Authorization");
            LAST_AUTHORIZATION_HEADER.set(authorization);

            var expectedAuthorization = EXPECTED_AUTHORIZATION.equals(authorization) || EXPLICIT_AUTHORIZATION_HEADER.equals(authorization);
            if (!expectedAuthorization) {
                writeUnauthorized(exchange);
                return;
            }

            var body = """
                {"_index":"auth_regression","_id":"doc-1","_version":1,"result":"created","_shards":{"total":1,"successful":1,"failed":0},"_seq_no":0,"_primary_term":1}
                """;
            var payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(201, payload.length);
            try (var output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        host = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void afterAll() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void writeUnauthorized(HttpExchange exchange) throws IOException {
        var payload = "unauthorized".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "text/plain");
        exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"test\"");
        exchange.sendResponseHeaders(401, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    @Test
    void shouldSendBasicAuthorizationHeaderWhenBasicAuthIsConfigured() throws Exception {
        var runContext = runContextFactory.of();
        var connection = OpensearchConnection.builder()
            .hosts(Property.ofValue(List.of(host)))
            .basicAuth(
                OpensearchConnection.BasicAuth.builder()
                    .username(Property.ofValue(USERNAME))
                    .password(Property.ofValue(PASSWORD))
                    .build()
            )
            .build();

        try (var transport = connection.client(runContext)) {
            var client = new OpenSearchClient(transport);
            var response = client.index(IndexRequest.of(builder -> builder
                .index("auth_regression")
                .id("doc-1")
                .document(Map.of("name", "john"))
            ));

            assertThat(response.id(), is("doc-1"));
        }
        assertThat(LAST_AUTHORIZATION_HEADER.get(), is(EXPECTED_AUTHORIZATION));
    }

    @Test
    void shouldSendExplicitAuthorizationHeaderWhenNoBasicAuthConfigured() throws Exception {
        var runContext = runContextFactory.of();
        var connection = OpensearchConnection.builder()
            .hosts(Property.ofValue(List.of(host)))
            .headers(Property.ofValue(List.of("Authorization: " + EXPLICIT_AUTHORIZATION_HEADER)))
            .build();

        try (var transport = connection.client(runContext)) {
            var client = new OpenSearchClient(transport);
            var response = client.index(IndexRequest.of(builder -> builder
                .index("auth_regression")
                .id("doc-1")
                .document(Map.of("name", "john"))
            ));

            assertThat(response.id(), is("doc-1"));
        }
        assertThat(LAST_AUTHORIZATION_HEADER.get(), is(EXPLICIT_AUTHORIZATION_HEADER));
    }
}
