package io.kestra.plugin.opensearch.shared;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.slf4j.Logger;

import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.executions.metrics.Timer;
import io.kestra.core.runners.RunContext;

import reactor.core.publisher.Flux;

import static io.kestra.core.utils.Rethrow.throwFunction;

/**
 * Shared bulk-indexing logic, consumed by both {@code plugin-opensearch} (OSS, via {@code AbstractLoad})
 * and {@code plugin-ee-opensearch} (EE, via {@code AbstractBulkTask}).
 */
public final class BulkService {
    private static final int MAX_LOGGED_ERRORS = 20;

    // Upper bound on how many operations may be buffered before the first bulk request leaves.
    // Neither consumer historically capped this; a large value would buffer that many operations
    // in memory, so guard against pathological input while leaving all reasonable batching (the
    // default chunk is 1000) untouched.
    static final int MAX_BUFFER_SIZE = 100_000;

    private BulkService() {
    }

    public static long executeBulk(
        RunContext runContext,
        RestClientTransport transport,
        Flux<BulkOperation> operationFlux,
        Integer bufferSize) throws IOException {
        if (bufferSize == null || bufferSize <= 0) {
            throw new IllegalArgumentException("chunk/bufferSize must be a positive integer");
        }
        if (bufferSize > MAX_BUFFER_SIZE) {
            throw new IllegalArgumentException("chunk/bufferSize must not exceed " + MAX_BUFFER_SIZE + " but was " + bufferSize);
        }

        Logger logger = runContext.logger();
        OpenSearchClient client = new OpenSearchClient(transport);
        AtomicLong count = new AtomicLong();
        AtomicLong duration = new AtomicLong();

        Flux<BulkResponse> bulkResponses = operationFlux
            .doOnNext(operation -> count.incrementAndGet())
            .buffer(bufferSize, bufferSize)
            .map(throwFunction(indexRequests ->
            {
                var bulkRequest = new BulkRequest.Builder();
                bulkRequest.operations(indexRequests);

                return client.bulk(bulkRequest.build());
            }))
            .doOnNext(bulkItemResponse ->
            {
                duration.addAndGet(bulkItemResponse.took());

                if (bulkItemResponse.errors()) {
                    throw new RuntimeException("Indexer failed bulk:\n " + logError(bulkItemResponse));
                }
            });

        // metrics & finalize
        Long requestCount = bulkResponses.count().blockOptional().orElse(0L);
        runContext.metric(Counter.of("requests.count", requestCount));
        runContext.metric(Counter.of("records", count.get()));
        runContext.metric(Timer.of("requests.duration", Duration.ofMillis(duration.get())));

        logger.info(
            "Successfully send {} requests for {} records in {}",
            requestCount,
            count.get(),
            Duration.ofMillis(duration.get())
        );
        return count.get();
    }

    private static String logError(BulkResponse bulkResponse) {
        StringBuilder builder = new StringBuilder();
        var errorCount = new AtomicLong();
        var loggedCount = new AtomicLong();
        bulkResponse.items().forEach(
            responseItem ->
            {
                if (responseItem.error() != null) {
                    errorCount.incrementAndGet();
                    if (loggedCount.get() < MAX_LOGGED_ERRORS) {
                        loggedCount.incrementAndGet();
                        builder
                            .append(responseItem.index()).append(": ")
                            .append(responseItem.status()).append(" - ")
                            .append(responseItem.error().reason()).append('\n');
                    }
                }
            }
        );

        var omittedCount = errorCount.get() - loggedCount.get();
        if (omittedCount > 0) {
            builder.append("... (").append(omittedCount).append(" more errors omitted)\n");
        }

        return builder.toString();
    }
}
