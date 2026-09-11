package io.kestra.plugin.opensearch.shared;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.opensearch.client.opensearch.core.bulk.OperationType;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import reactor.core.publisher.Flux;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

// mockConstruction(OpenSearchClient.class) is not safe across concurrently running threads on the same class
@Execution(ExecutionMode.SAME_THREAD)
@KestraTest
class BulkServiceTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldRecordMetricsAndReturnCountOnSuccessfulBulk() throws Exception {
        var runContext = runContextFactory.of();
        var transport = mock(RestClientTransport.class);

        var operations = Flux.fromIterable(List.of(
            indexOperation("1"),
            indexOperation("2"),
            indexOperation("3")
        ));

        try (var ignored = mockConstruction(OpenSearchClient.class, (client, context) ->
            when(client.bulk(any(BulkRequest.class))).thenReturn(successfulResponse()))) {

            var count = BulkService.executeBulk(runContext, transport, operations, 10);

            assertThat(count, is(3L));
        }
        assertThat(runContext.metrics().stream().filter(e -> e.getName().equals("requests.count")).findFirst().orElseThrow().getValue(), is(1D));
        assertThat(runContext.metrics().stream().filter(e -> e.getName().equals("records")).findFirst().orElseThrow().getValue(), is(3D));
        assertThat(runContext.metrics().stream().filter(e -> e.getName().equals("requests.duration")).findFirst().orElseThrow().getValue(), is(Duration.ofNanos(42)));
    }

    @Test
    void shouldChunkAcrossMultipleBuffersWhenExceedingBufferSize() throws Exception {
        var runContext = runContextFactory.of();
        var transport = mock(RestClientTransport.class);

        var operations = Flux.fromIterable(List.of(
            indexOperation("1"),
            indexOperation("2"),
            indexOperation("3"),
            indexOperation("4"),
            indexOperation("5")
        ));

        try (var ignored = mockConstruction(OpenSearchClient.class, (client, context) ->
            when(client.bulk(any(BulkRequest.class))).thenReturn(successfulResponse()))) {

            var count = BulkService.executeBulk(runContext, transport, operations, 2);

            assertThat(count, is(5L));
        }
        // 5 records buffered by 2 => 3 bulk requests (2 + 2 + 1)
        assertThat(runContext.metrics().stream().filter(e -> e.getName().equals("requests.count")).findFirst().orElseThrow().getValue(), is(3D));
        assertThat(runContext.metrics().stream().filter(e -> e.getName().equals("records")).findFirst().orElseThrow().getValue(), is(5D));
    }

    @Test
    void shouldThrowWithAggregatedErrorsWhenBulkResponseHasErrors() throws Exception {
        var runContext = runContextFactory.of();
        var transport = mock(RestClientTransport.class);

        var operations = Flux.just(indexOperation("1"));

        try (var ignored = mockConstruction(OpenSearchClient.class, (client, context) ->
            when(client.bulk(any(BulkRequest.class))).thenReturn(failedResponse()))) {

            var exception = assertThrows(RuntimeException.class, () -> BulkService.executeBulk(runContext, transport, operations, 10));

            assertThat(exception.getMessage(), containsString("Indexer failed bulk"));
            assertThat(exception.getMessage(), containsString("ut_index"));
            assertThat(exception.getMessage(), containsString("document already exists"));
        }
    }

    @Test
    void shouldRejectNullOrNonPositiveBufferSize() {
        var runContext = runContextFactory.of();
        var transport = mock(RestClientTransport.class);
        var operations = Flux.just(indexOperation("1"));

        var nullBufferException = assertThrows(IllegalArgumentException.class,
            () -> BulkService.executeBulk(runContext, transport, operations, null));
        assertThat(nullBufferException.getMessage(), containsString("chunk/bufferSize must be a positive integer"));

        var zeroBufferException = assertThrows(IllegalArgumentException.class,
            () -> BulkService.executeBulk(runContext, transport, operations, 0));
        assertThat(zeroBufferException.getMessage(), containsString("chunk/bufferSize must be a positive integer"));
    }

    private BulkOperation indexOperation(String id) {
        return BulkOperation.of(builder -> builder
            .index(IndexOperation.of((IndexOperation.Builder<Object> indexBuilder) -> indexBuilder
                .index("ut_index")
                .id(id)
                .document(Map.of("field", "value"))
            ))
        );
    }

    private BulkResponse successfulResponse() {
        return BulkResponse.of(builder -> builder
            .errors(false)
            .took(42)
            .items(List.of(
                bulkResponseItem("1", null)
            ))
        );
    }

    private BulkResponse failedResponse() {
        return BulkResponse.of(builder -> builder
            .errors(true)
            .took(1)
            .items(List.of(bulkResponseItem("1", "document already exists")))
        );
    }

    private BulkResponseItem bulkResponseItem(String id, String errorReason) {
        return BulkResponseItem.of(builder ->
        {
            builder
                .operationType(OperationType.Index)
                .index("ut_index")
                .id(id)
                .status(errorReason == null ? 201 : 409);

            if (errorReason != null) {
                builder.error(errorBuilder -> errorBuilder.type("version_conflict_engine_exception").reason(errorReason));
            }

            return builder;
        });
    }
}
