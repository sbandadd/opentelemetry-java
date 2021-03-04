/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.trace.export;

import io.opentelemetry.api.metrics.BoundLongCounter;
import io.opentelemetry.api.metrics.GlobalMetricsProvider;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.common.Labels;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.internal.DaemonThreadFactory;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of the {@link SpanProcessor} that batches spans exported by the SDK then pushes
 * them to the exporter pipeline.
 *
 * <p>All spans reported by the SDK implementation are first added to a synchronized queue (with a
 * {@code maxQueueSize} maximum size, if queue is full spans are dropped). Spans are exported either
 * when there are {@code maxExportBatchSize} pending spans or {@code scheduleDelayNanos} has passed
 * since the last export finished.
 *
 * <p>This batch {@link SpanProcessor} can cause high contention in a very high traffic service.
 * TODO: Add a link to the SpanProcessor that uses Disruptor as alternative with low contention.
 */
public final class BatchSpanProcessor implements SpanProcessor {

  private static final String WORKER_THREAD_NAME =
      BatchSpanProcessor.class.getSimpleName() + "_WorkerThread";
  private static final String SPAN_PROCESSOR_TYPE_LABEL = "spanProcessorType";
  private static final String SPAN_PROCESSOR_TYPE_VALUE = BatchSpanProcessor.class.getSimpleName();

  private final Worker worker;
  private final AtomicBoolean isShutdown = new AtomicBoolean(false);

  /**
   * Returns a new Builder for {@link BatchSpanProcessor}.
   *
   * @param spanExporter the {@code SpanExporter} to where the Spans are pushed.
   * @return a new {@link BatchSpanProcessor}.
   * @throws NullPointerException if the {@code spanExporter} is {@code null}.
   */
  public static BatchSpanProcessorBuilder builder(SpanExporter spanExporter) {
    return new BatchSpanProcessorBuilder(spanExporter);
  }

  BatchSpanProcessor(
      SpanExporter spanExporter,
      long scheduleDelayNanos,
      int maxQueueSize,
      int maxExportBatchSize,
      long exporterTimeoutNanos) {
    this.worker =
        new Worker(
            spanExporter,
            scheduleDelayNanos,
            maxExportBatchSize,
            exporterTimeoutNanos,
            new ConcurrentLinkedQueue<SpanData>(),
            maxQueueSize);
    Thread workerThread = new DaemonThreadFactory(WORKER_THREAD_NAME).newThread(worker);
    workerThread.start();
  }

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {}

  @Override
  public boolean isStartRequired() {
    return false;
  }

  @Override
  public void onEnd(ReadableSpan span) {
    if (!span.getSpanContext().isSampled()) {
      return;
    }
    worker.addSpan(span);
  }

  @Override
  public boolean isEndRequired() {
    return true;
  }

  @Override
  public CompletableResultCode shutdown() {
    if (isShutdown.getAndSet(true)) {
      return CompletableResultCode.ofSuccess();
    }
    return worker.shutdown();
  }

  @Override
  public CompletableResultCode forceFlush() {
    return worker.forceFlush();
  }

  // Worker is a thread that batches multiple spans and calls the registered SpanExporter to export
  // the data.
  private static final class Worker implements Runnable {

    private final BoundLongCounter droppedSpans;
    private final BoundLongCounter exportedSpans;

    private static final Logger logger = Logger.getLogger(Worker.class.getName());
    private final SpanExporter spanExporter;
    private final long scheduleDelayNanos;
    private final int maxExportBatchSize;
    private final long exporterTimeoutNanos;
    private final ConcurrentLinkedQueue<SpanData> queue;
    private final AtomicInteger queueSize;
    private volatile boolean continueWork = true;
    private final Collection<SpanData> batch;
    private final int maxQueueSize;
    private final ReentrantLock lock;
    private final Condition needExport;
    private final AtomicReference<CompletableResultCode> flushRequested = new AtomicReference<>();
    private long nextExportTime;

    private Worker(
        SpanExporter spanExporter,
        long scheduleDelayNanos,
        int maxExportBatchSize,
        long exporterTimeoutNanos,
        ConcurrentLinkedQueue<SpanData> queue,
        int maxQueueSize) {
      this.spanExporter = spanExporter;
      this.scheduleDelayNanos = scheduleDelayNanos;
      this.maxExportBatchSize = maxExportBatchSize;
      this.exporterTimeoutNanos = exporterTimeoutNanos;
      this.queue = queue;
      this.queueSize = new AtomicInteger(0);
      this.maxQueueSize = maxQueueSize;
      this.lock = new ReentrantLock();
      this.needExport = lock.newCondition();
      Meter meter = GlobalMetricsProvider.getMeter("io.opentelemetry.sdk.trace");
      meter
          .longValueObserverBuilder("queueSize")
          .setDescription("The number of spans queued")
          .setUnit("1")
          .setUpdater(
              result ->
                  result.observe(
                      queueSize.get(),
                      Labels.of(SPAN_PROCESSOR_TYPE_LABEL, SPAN_PROCESSOR_TYPE_VALUE)))
          .build();
      LongCounter processedSpansCounter =
          meter
              .longCounterBuilder("processedSpans")
              .setUnit("1")
              .setDescription(
                  "The number of spans processed by the BatchSpanProcessor. "
                      + "[dropped=true if they were dropped due to high throughput]")
              .build();
      droppedSpans =
          processedSpansCounter.bind(
              Labels.of(SPAN_PROCESSOR_TYPE_LABEL, SPAN_PROCESSOR_TYPE_VALUE, "dropped", "true"));
      exportedSpans =
          processedSpansCounter.bind(
              Labels.of(SPAN_PROCESSOR_TYPE_LABEL, SPAN_PROCESSOR_TYPE_VALUE, "dropped", "false"));

      this.batch = new ArrayList<>(this.maxExportBatchSize);
    }

    private void addSpan(ReadableSpan span) {
      if (queueSize.get() == maxQueueSize) {
        droppedSpans.add(1);
      } else {
        queue.offer(span.toSpanData());
        int newQueueSize = queueSize.incrementAndGet();
        if (newQueueSize >= maxExportBatchSize) {
          lock.lock();
          try {
            needExport.signal();
          } finally {
            lock.unlock();
          }
        }
      }
    }

    @Override
    public void run() {
      updateNextExportTime();
      while (continueWork) {
        if (flushRequested.get() != null) {
          flush();
        }
        lock.lock();
        try {
          long pollWaitTime = nextExportTime - System.nanoTime();
          if (pollWaitTime > 0) {
            needExport.awaitNanos(pollWaitTime);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } finally {
          lock.unlock();
        }
        while (!queue.isEmpty() && batch.size() < maxExportBatchSize) {
          batch.add(queue.poll());
          queueSize.decrementAndGet();
        }
        if (batch.size() >= maxExportBatchSize || System.nanoTime() >= nextExportTime) {
          exportCurrentBatch();
          updateNextExportTime();
        }
      }
    }

    private void flush() {
      while (!queue.isEmpty()) {
        batch.add(queue.poll());
        queueSize.decrementAndGet();
        if (batch.size() >= maxExportBatchSize) {
          exportCurrentBatch();
        }
      }
      exportCurrentBatch();
      flushRequested.get().succeed();
      flushRequested.set(null);
    }

    private void updateNextExportTime() {
      nextExportTime = System.nanoTime() + scheduleDelayNanos;
    }

    private CompletableResultCode shutdown() {
      final CompletableResultCode result = new CompletableResultCode();

      final CompletableResultCode flushResult = forceFlush();
      flushResult.whenComplete(
          () -> {
            continueWork = false;
            final CompletableResultCode shutdownResult = spanExporter.shutdown();
            shutdownResult.whenComplete(
                () -> {
                  if (!flushResult.isSuccess() || !shutdownResult.isSuccess()) {
                    result.fail();
                  } else {
                    result.succeed();
                  }
                });
          });

      return result;
    }

    private CompletableResultCode forceFlush() {
      CompletableResultCode flushResult = new CompletableResultCode();
      // we set the atomic here to trigger the worker loop to do a flush of the entire queue.
      if (flushRequested.compareAndSet(null, flushResult)) {
        lock.lock();
        try {
          needExport.signal();
        } finally {
          lock.unlock();
        }
      }
      CompletableResultCode possibleResult = flushRequested.get();
      // there's a race here where the flush happening in the worker loop could complete before we
      // get what's in the atomic. In that case, just return success, since we know it succeeded in
      // the interim.
      return possibleResult == null ? CompletableResultCode.ofSuccess() : possibleResult;
    }

    private void exportCurrentBatch() {
      if (batch.isEmpty()) {
        return;
      }

      try {
        final CompletableResultCode result = spanExporter.export(batch);
        result.join(exporterTimeoutNanos, TimeUnit.NANOSECONDS);
        if (result.isSuccess()) {
          exportedSpans.add(batch.size());
        } else {
          logger.log(Level.FINE, "Exporter failed");
        }
      } catch (RuntimeException e) {
        logger.log(Level.WARNING, "Exporter threw an Exception", e);
      } finally {
        batch.clear();
      }
    }
  }
}
