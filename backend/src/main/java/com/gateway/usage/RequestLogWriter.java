package com.gateway.usage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Takes request logs off the request path: {@link #submit} only does a non-blocking queue offer,
 * and a scheduled flush writes them to Postgres in batches.
 */
@Component
public class RequestLogWriter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogWriter.class);

    private final BlockingQueue<RequestLog> queue;
    private final RequestLogRepository repository;
    private final int batchSize;
    private final Counter droppedCounter;
    private final Counter failedCounter;

    RequestLogWriter(RequestLogRepository repository, UsageProperties properties, MeterRegistry meterRegistry) {
        this.queue = new ArrayBlockingQueue<>(properties.queueCapacity());
        this.repository = repository;
        this.batchSize = properties.batchSize();
        this.droppedCounter = Counter.builder("gateway.usage.logs.dropped").register(meterRegistry);
        this.failedCounter = Counter.builder("gateway.usage.logs.failed").register(meterRegistry);
    }

    public void submit(RequestLog entry) {
        if (!queue.offer(entry)) {
            droppedCounter.increment();
        }
    }

    @Scheduled(fixedDelayString = "${gateway.usage.flush-interval}")
    void flush() {
        var batch = new ArrayList<RequestLog>(batchSize);
        while (queue.drainTo(batch, batchSize) > 0) {
            write(batch);
            batch.clear();
        }
    }

    /** Writes whatever is still queued so a graceful shutdown loses nothing. */
    @PreDestroy
    void flushOnShutdown() {
        flush();
    }

    private void write(List<RequestLog> batch) {
        try {
            repository.insertAll(batch);
        } catch (DataAccessException ex) {
            failedCounter.increment(batch.size());
            log.error("Failed to write {} request logs; they are discarded", batch.size(), ex);
        }
    }
}
