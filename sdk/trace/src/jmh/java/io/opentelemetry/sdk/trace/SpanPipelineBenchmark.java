/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.trace;

import io.github.pixee.security.HostValidator;
import io.github.pixee.security.Urls;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public class SpanPipelineBenchmark {
  private SpanPipelineBenchmark() {}

  @State(Scope.Benchmark)
  public abstract static class AbstractProcessorBenchmark {
    private static final DockerImageName OTLP_COLLECTOR_IMAGE =
        DockerImageName.parse("otel/opentelemetry-collector-dev:latest");
    private static final int EXPOSED_PORT = 5678;
    private static final int HEALTH_CHECK_PORT = 13133;
    private Tracer tracer;
    private SdkTracerProvider tracerProvider;

    protected abstract SpanProcessor getSpanProcessor(String collectorAddress);

    protected abstract void runThePipeline();

    protected void doWork() {
      for (int j = 0; j < 100; j++) {
        Span span = tracer.spanBuilder("PipelineBenchmarkSpan " + j).startSpan();
        for (int i = 0; i < 10; i++) {
          span.setAttribute("benchmarkAttribute_" + i, "benchmarkAttrValue_" + i);
        }
        span.end();
      }
      // we flush the SDK in order to make sure that the BatchSpanProcessor doesn't drop spans.
      // this means that this benchmark is mostly useful for measuring allocations, not throughput.
      tracerProvider.forceFlush().join(1, TimeUnit.SECONDS);
    }

    @Setup(Level.Trial)
    @SuppressWarnings("SystemOut")
    public void setup() {
      // Configuring the collector test-container
      GenericContainer<?> collector =
          new GenericContainer<>(OTLP_COLLECTOR_IMAGE)
              .withExposedPorts(EXPOSED_PORT, HEALTH_CHECK_PORT)
              .waitingFor(Wait.forHttp("/").forPort(HEALTH_CHECK_PORT))
              .withCopyFileToContainer(
                  MountableFile.forClasspathResource("/otel.yaml"), "/etc/otel.yaml")
              .withCommand("--config /etc/otel.yaml");

      collector.start();

      SpanProcessor spanProcessor = makeSpanProcessor(collector);

      tracerProvider =
          SdkTracerProvider.builder()
              .setSampler(Sampler.alwaysOn())
              .addSpanProcessor(spanProcessor)
              .build();

      tracer = tracerProvider.get("PipelineBenchmarkTracer");
    }

    private SpanProcessor makeSpanProcessor(GenericContainer<?> collector) {
      try {
        String host = collector.getHost();
        Integer port = collector.getMappedPort(EXPOSED_PORT);
        String address = Urls.create("http", host, port, "", Urls.HTTP_PROTOCOLS, HostValidator.DENY_COMMON_INFRASTRUCTURE_TARGETS).toString();
        return getSpanProcessor(address);
      } catch (MalformedURLException e) {
        throw new IllegalStateException("can't make a url", e);
      }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Warmup(iterations = 5, time = 1)
    @Measurement(iterations = 15, time = 1)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Fork(1)
    @Threads(1)
    public void measureSpanPipeline() {
      runThePipeline();
    }
  }

  public static class SimpleSpanProcessorBenchmark extends AbstractProcessorBenchmark {
    @Override
    protected SpanProcessor getSpanProcessor(String collectorAddress) {
      return SimpleSpanProcessor.create(
          OtlpGrpcSpanExporter.builder()
              .setEndpoint(collectorAddress)
              .setTimeout(Duration.ofSeconds(50))
              .build());
    }

    @Override
    protected void runThePipeline() {
      doWork();
    }
  }

  public static class BatchSpanProcessorBenchmark extends AbstractProcessorBenchmark {

    @Override
    protected SpanProcessor getSpanProcessor(String collectorAddress) {
      return BatchSpanProcessor.builder(
              OtlpGrpcSpanExporter.builder()
                  .setEndpoint(collectorAddress)
                  .setTimeout(Duration.ofSeconds(50))
                  .build())
          .build();
    }

    @Override
    protected void runThePipeline() {
      doWork();
    }
  }
}
