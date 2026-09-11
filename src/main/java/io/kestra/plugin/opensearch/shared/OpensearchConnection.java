package io.kestra.plugin.opensearch.shared;

import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.ssl.TrustStrategy;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.transport.rest_client.RestClientTransport;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Shared OpenSearch connection, consumed by both {@code plugin-opensearch} (OSS) and
 * {@code plugin-ee-opensearch} (EE). Any change here affects both plugins' {@code connection.*}
 * property surface — keep behaviour identical for both.
 */
@SuperBuilder
@NoArgsConstructor
@Getter
public class OpensearchConnection {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson(false);

    @Schema(
        title = "OpenSearch HTTP endpoints",
        description = "One or more host URLs with scheme and port, e.g. `https://opensearch.com:9200`; all are used for load-balancing/failover."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<String>> hosts;

    @Schema(
        title = "Basic auth configuration"
    )
    @PluginProperty(dynamic = false, group = "advanced")
    private BasicAuth basicAuth;

    @Schema(
        title = "Additional HTTP headers",
        description = "Each entry is `Key:Value`, e.g. `Authorization: Token XYZ`; rendered per request."
    )
    @PluginProperty(secret = true, group = "advanced")
    private Property<List<String>> headers;

    @Schema(
        title = "Path prefix for every request",
        description = "Prepends `/my/path` to all endpoints when OpenSearch is behind a proxy enforcing a base path; leave unset otherwise."
    )
    @PluginProperty(group = "advanced")
    private Property<String> pathPrefix;

    @Schema(
        title = "Fail on warning headers",
        description = "If true, any response containing an OpenSearch warning header is treated as a failure; defaults to server/client behavior."
    )
    @PluginProperty(group = "advanced")
    private Property<Boolean> strictDeprecationMode;

    @Schema(
        title = "Trust all SSL certificates",
        description = "WARNING — SECURITY RISK: When enabled, disables TLS certificate chain validation, allowing connections " +
            "to servers with self-signed or otherwise untrusted certificates. Hostname verification remains enforced " +
            "to reduce (but not eliminate) exposure to man-in-the-middle attacks. Must never be used against production " +
            "clusters or over untrusted networks. Prefer supplying a trusted CA certificate/trust store instead."
    )
    @PluginProperty(group = "advanced")
    private Property<Boolean> trustAllSsl;

    @SuperBuilder
    @NoArgsConstructor
    @Getter
    public static class BasicAuth {
        @Schema(
            title = "Basic auth username"
        )
        @PluginProperty(secret = true, group = "connection")
        private Property<String> username;

        @Schema(
            title = "Basic auth password"
        )
        @PluginProperty(secret = true, group = "connection")
        private Property<String> password;
    }

    public RestClientTransport client(RunContext runContext) throws IllegalVariableEvaluationException {
        RestClientBuilder.HttpClientConfigCallback configCallback = httpClientBuilder ->
        {
            try {
                return this.httpAsyncClientBuilder(runContext, httpClientBuilder);
            } catch (NoSuchAlgorithmException | KeyStoreException | KeyManagementException | IllegalVariableEvaluationException e) {
                throw new RuntimeException(e);
            }
        };
        RestClientBuilder builder = RestClient
            .builder(this.httpHosts(runContext))
            .setHttpClientConfigCallback(configCallback);

        if (this.getHeaders() != null) {
            builder.setDefaultHeaders(this.defaultHeaders(runContext));
        }

        if (this.getPathPrefix() != null) {
            builder.setPathPrefix(runContext.render(this.pathPrefix).as(String.class)
                .orElseThrow(() -> new IllegalArgumentException("Missing required property: pathPrefix")));
        }

        if (this.getStrictDeprecationMode() != null) {
            builder.setStrictDeprecationMode(runContext.render(this.getStrictDeprecationMode()).as(Boolean.class)
                .orElseThrow(() -> new IllegalArgumentException("Missing required property: strictDeprecationMode")));
        }

        return new RestClientTransport(builder.build(), new JacksonJsonpMapper(MAPPER));
    }

    private HttpAsyncClientBuilder httpAsyncClientBuilder(RunContext runContext, HttpAsyncClientBuilder builder)
        throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException, IllegalVariableEvaluationException {

        builder.setUserAgent("Kestra/" + runContext.version());

        if (basicAuth != null) {
            final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            String renderedPassword = runContext.render(this.basicAuth.password).as(String.class).orElse(null);

            credentialsProvider.setCredentials(
                new AuthScope(null, -1),
                new UsernamePasswordCredentials(
                    runContext.render(this.basicAuth.username).as(String.class)
                        .orElseThrow(() -> new IllegalArgumentException("Missing required property: basicAuth.username")),
                    (renderedPassword != null) ? renderedPassword.toCharArray() : null
                )
            );
            builder.setDefaultCredentialsProvider(credentialsProvider);
        }

        if (Boolean.TRUE.equals(runContext.render(trustAllSsl).as(Boolean.class).orElse(false))) {
            runContext.logger().warn(
                "trustAllSsl=true: TLS certificate chain validation is DISABLED for this OpenSearch connection. " +
                "This is INSECURE and must never be used against production clusters or over untrusted networks, " +
                "as it exposes the connection to man-in-the-middle attacks. Prefer supplying a trusted CA certificate/trust store instead."
            );

            SSLContextBuilder sslContextBuilder = new SSLContextBuilder();
            sslContextBuilder.loadTrustMaterial(null, (TrustStrategy) (chain, authType) -> true);
            SSLContext sslContext = sslContextBuilder.build();

            // Even when trusting all certificate chains, hostname verification must remain enabled
            // to prevent man-in-the-middle attacks (CWE-295 / CWE-297).
            final TlsStrategy tlsStrategy = ClientTlsStrategyBuilder.create()
                .setSslContext(sslContext)
                .buildAsync();

            builder.setConnectionManager(
                PoolingAsyncClientConnectionManagerBuilder.create()
                    .setTlsStrategy(tlsStrategy)
                    .build()
            );
        }

        return builder;
    }

    private HttpHost[] httpHosts(RunContext runContext) throws IllegalVariableEvaluationException {
        return runContext.render(this.hosts).asList(String.class)
            .stream()
            .map(s ->
            {
                URI uri = URI.create(s);
                return new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort());
            })
            .toArray(HttpHost[]::new);
    }

    private Header[] defaultHeaders(RunContext runContext) throws IllegalVariableEvaluationException {
        return runContext.render(this.headers).asList(String.class)
            .stream()
            .map(header ->
            {
                String[] nameAndValue = header.split(":", 2);
                if (nameAndValue.length != 2) {
                    throw new IllegalArgumentException("Invalid header format, expected `Name: Value` but got `" + header + "`");
                }
                return new BasicHeader(nameAndValue[0].trim(), nameAndValue[1].trim());
            })
            .toArray(Header[]::new);
    }
}
