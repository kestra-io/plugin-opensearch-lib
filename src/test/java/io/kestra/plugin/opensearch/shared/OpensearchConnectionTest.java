package io.kestra.plugin.opensearch.shared;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;
import org.opensearch.client.opensearch.OpenSearchClient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class OpensearchConnectionTest {
    private static GenericContainer<?> opensearch;
    private static String host;

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void beforeAll() {
        opensearch = new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:2.19.1"))
            .withEnv("discovery.type", "single-node")
            .withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "MyPassW0rd001!")
            .withEnv("plugins.security.disabled", "true")
            .withExposedPorts(9200);
        opensearch.start();
        host = "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200);
    }

    @AfterAll
    static void afterAll() {
        if (opensearch != null) {
            opensearch.stop();
        }
    }

    @Test
    void shouldConnectAndFetchClusterInfoFromHostsList() throws Exception {
        var runContext = runContextFactory.of();
        var connection = OpensearchConnection.builder()
            .hosts(Property.ofValue(List.of(host)))
            .build();

        try (var transport = connection.client(runContext)) {
            var client = new OpenSearchClient(transport);
            var info = client.info();

            assertThat(info.clusterName(), notNullValue());
        }
    }

    @Test
    void shouldApplyPathPrefixToEveryRequest() throws Exception {
        var runContext = runContextFactory.of();
        var connection = OpensearchConnection.builder()
            .hosts(Property.ofValue(List.of(host)))
            .pathPrefix(Property.ofValue("/does-not-exist"))
            .build();

        try (var transport = connection.client(runContext)) {
            var client = new OpenSearchClient(transport);
            // `/info` on the real endpoint becomes `/does-not-exist/info` once the prefix is applied,
            // which OpenSearch's root handler resolves as "look up index `does-not-exist`" — a 404
            // that proves the prefix reached the request, since without it `.info()` always succeeds
            var exception = assertThrows(Exception.class, client::info);
            assertThat(exception.getMessage(), containsString("index_not_found_exception"));
        }
    }

    @Test
    void shouldConnectWhenStrictDeprecationModeIsEnabled() throws Exception {
        var runContext = runContextFactory.of();
        var connection = OpensearchConnection.builder()
            .hosts(Property.ofValue(List.of(host)))
            .strictDeprecationMode(Property.ofValue(true))
            .build();

        try (var transport = connection.client(runContext)) {
            var client = new OpenSearchClient(transport);
            var info = client.info();

            assertThat(info.clusterName(), notNullValue());
        }
    }

    @Test
    void shouldRejectMalformedHeader() {
        var runContext = runContextFactory.of();
        var connection = OpensearchConnection.builder()
            .hosts(Property.ofValue(List.of(host)))
            .headers(Property.ofValue(List.of("NoColonHere")))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> connection.client(runContext));
        assertThat(exception.getMessage(), containsString("Invalid header format, expected `Name: Value` but got `NoColonHere`"));
    }
}
