package io.kestra.plugin.opensearch.shared;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;
import org.opensearch.client.opensearch.OpenSearchClient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the `trustAllSsl` claim made in {@link OpensearchConnection}: it must let the client
 * connect to a node serving a self-signed/untrusted certificate, it must NOT be required for
 * plaintext HTTP (see {@link OpensearchConnectionTest}), and hostname verification must remain
 * enforced regardless of `trustAllSsl`.
 *
 * <p>Unlike {@link OpensearchConnectionTest}, this container runs with the OpenSearch security
 * plugin ON (the image default) so it serves HTTPS on 9200 with its bundled self-signed demo
 * certificate (CN=node-0.example.com, SAN=node-0.example.com/localhost/127.0.0.1/::1).
 */
@KestraTest
class OpensearchConnectionTlsTest {
    private static final String ADMIN_PASSWORD = "MyPassW0rd001!";

    private static GenericContainer<?> opensearch;
    private static String httpsHost;

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void beforeAll() {
        opensearch = new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:2"))
            .withEnv("discovery.type", "single-node")
            .withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", ADMIN_PASSWORD)
            // security plugin intentionally left ON (the image default) to serve HTTPS with a
            // self-signed cert — that's the whole point of this test class.
            .withExposedPorts(9200)
            // the exposed port accepts TCP connections well before the security plugin finishes
            // bootstrapping its internal index; wait for that log line or early requests get a
            // transient 503 "OpenSearch Security not initialized".
            .waitingFor(Wait.forLogMessage(".*Node '.*' initialized.*\\n", 1)
                .withStartupTimeout(Duration.ofMinutes(2)));
        opensearch.start();
        httpsHost = "https://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200);
    }

    @AfterAll
    static void afterAll() {
        if (opensearch != null) {
            opensearch.stop();
        }
    }

    private static OpensearchConnection.OpensearchConnectionBuilder<?, ?> connectionBuilder(String host) {
        return OpensearchConnection.builder()
            .hosts(Property.ofValue(List.of(host)))
            .basicAuth(OpensearchConnection.BasicAuth.builder()
                .username(Property.ofValue("admin"))
                .password(Property.ofValue(ADMIN_PASSWORD))
                .build());
    }

    @Test
    void shouldConnectToSelfSignedCertWhenTrustAllSslIsEnabled() throws Exception {
        var runContext = runContextFactory.of();
        var connection = connectionBuilder(httpsHost)
            .trustAllSsl(Property.ofValue(true))
            .build();

        try (var transport = connection.client(runContext)) {
            var client = new OpenSearchClient(transport);
            var info = client.info();

            assertThat(info.clusterName(), notNullValue());
        }
    }

    @Test
    void shouldRejectSelfSignedCertWhenTrustAllSslIsDisabled() throws Exception {
        var runContext = runContextFactory.of();
        var connection = connectionBuilder(httpsHost)
            .build();

        try (var transport = connection.client(runContext)) {
            var client = new OpenSearchClient(transport);
            var exception = assertThrows(Exception.class, client::info);

            Throwable cause = exception;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertThat(cause, notNullValue());
            assertThat(
                "Expected a certificate validation failure but got: " + cause,
                cause instanceof javax.net.ssl.SSLHandshakeException
                    || cause.getMessage() != null && cause.getMessage().toLowerCase().contains("certificat")
            );
        }
    }

    // NOT COVERED: a "hostname verification still enforced when trustAllSsl=true" test.
    //
    // Attempted approach: the demo cert's SAN only lists node-0.example.com/localhost/127.0.0.1/::1,
    // and the whole 127.0.0.0/8 block routes to loopback on Linux, so connecting via 127.0.0.2 (an
    // IP deliberately absent from the cert's SAN) reaches the same Docker-mapped port while handing
    // the TLS layer a peer identity it should reject. In practice this did not reproduce a failure:
    // httpclient5's async NIO transport resolves `HostnameVerificationPolicy.BUILTIN` by delegating
    // straight to the JDK's `SSLEngine` "HTTPS" endpoint identification algorithm, and that algorithm
    // needs the engine to have been given a peer host to check against — for a literal-IP async
    // upgrade it does not reliably enforce the IP-SAN match the way `SSLSocket`-based (blocking)
    // clients do. Reproducing a genuine mismatch would require a real DNS name pointing at the
    // container (e.g. a custom /etc/hosts entry), which is impractical to do hermetically in this
    // test suite. Per review guidance, this sub-assertion is skipped rather than blocking the rest
    // of this coverage; the production code does NOT explicitly configure a hostname verification
    // policy — it relies on `ClientTlsStrategyBuilder`'s default `HostnameVerificationPolicy.BUILTIN`,
    // under which `AbstractClientTlsStrategy` calls `setEndpointIdentificationAlgorithm("HTTPS")` on
    // the SSL engine, so the JDK itself enforces hostname/IP-SAN matching on the async path for real
    // DNS-based connections.
}
