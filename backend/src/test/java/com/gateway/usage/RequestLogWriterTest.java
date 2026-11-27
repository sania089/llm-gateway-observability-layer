package com.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class RequestLogWriterTest {

    private final RequestLogRepository repository = mock(RequestLogRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private RequestLogWriter writerWith(int queueCapacity, int batchSize) {
        return new RequestLogWriter(repository, new UsageProperties(queueCapacity, batchSize, Duration.ofMillis(10)),
                meterRegistry);
    }

    private static RequestLog entry(String model) {
        return new RequestLog(Instant.now(), UUID.randomUUID(), model, "fake", 5, 200, 1, 2, 3, null);
    }

    @Test
    void writesQueuedEntriesInBatchesOfTheConfiguredSize() {
        var batchSizes = new ArrayList<Integer>();
        doAnswer(invocation -> batchSizes.add(invocation.<List<RequestLog>>getArgument(0).size()))
                .when(repository).insertAll(anyList());
        var writer = writerWith(10, 2);
        for (int i = 0; i < 5; i++) {
            writer.submit(entry("m" + i));
        }

        writer.flush();

        assertThat(batchSizes).containsExactly(2, 2, 1);
    }

    @Test
    void doesNothingWhenQueueIsEmpty() {
        writerWith(10, 2).flush();

        verify(repository, never()).insertAll(anyList());
    }

    @Test
    void dropsAndCountsEntriesWhenQueueIsFullInsteadOfBlocking() {
        var writer = writerWith(1, 10);

        writer.submit(entry("kept"));
        writer.submit(entry("dropped"));

        assertThat(meterRegistry.get("gateway.usage.logs.dropped").counter().count()).isEqualTo(1);
    }

    @Test
    void survivesDatabaseFailureAndCountsLostEntries() {
        doThrow(new DataAccessResourceFailureException("db down")).when(repository).insertAll(anyList());
        var writer = writerWith(10, 10);
        writer.submit(entry("a"));
        writer.submit(entry("b"));

        assertThatCode(writer::flush).doesNotThrowAnyException();

        assertThat(meterRegistry.get("gateway.usage.logs.failed").counter().count()).isEqualTo(2);
    }

    @Test
    void truncatesOverlongModelNamesSoTheyCannotFailABatch() {
        assertThat(entry("x".repeat(500)).model()).hasSize(RequestLog.MAX_MODEL_LENGTH);
    }
}
