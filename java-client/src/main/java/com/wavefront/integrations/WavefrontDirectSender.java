package com.wavefront.integrations;

import com.wavefront.api.DataIngestorAPI;
import com.wavefront.common.NamedThreadFactory;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpClientConnection;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestExecutor;
import org.jboss.resteasy.client.jaxrs.ClientHttpEngine;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.client.jaxrs.engines.ApacheHttpClient4Engine;
import org.jboss.resteasy.plugins.interceptors.encoding.AcceptEncodingGZIPFilter;
import org.jboss.resteasy.plugins.interceptors.encoding.GZIPDecodingInterceptor;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import javax.ws.rs.core.Response;

/**
 * Wavefront Client that sends data directly to Wavefront via the direct ingestion APIs.
 *
 * @author Vikram Raman (vikram@wavefront.com)
 */
public class WavefrontDirectSender implements WavefrontSender, Runnable {

  private static final String DEFAULT_SOURCE = "wavefrontDirectSender";
  private static final Logger LOGGER = LoggerFactory.getLogger(WavefrontDirectSender.class);
  private static String quote = "\"";
  private static String escapedQuote = "\\\"";

  private final Object pointsMutex = new Object();
  private final ScheduledExecutorService scheduler;
  private final String server;
  private final String token;
  private DataIngestorAPI directService;
  private CloseableHttpClient httpClient;
  private Future<?> scheduledFuture;

  //TODO: bounded buffer?
  private List<String> buffer = new ArrayList<>();

  public WavefrontDirectSender(String server, String token) {
    this.server = server;
    this.token = token;
    scheduler = Executors.newScheduledThreadPool(1, new NamedThreadFactory(DEFAULT_SOURCE));
  }

  private DataIngestorAPI createWavefrontService(String server, String token) {
    httpClient = HttpClientBuilder.create().
        setUserAgent(DEFAULT_SOURCE).
        setRequestExecutor(new TokenHeaderHttpRequestExectutor(token)).
        build();

    final ApacheHttpClient4Engine apacheHttpClient4Engine = new ApacheHttpClient4Engine(httpClient, true);
    apacheHttpClient4Engine.setFileUploadInMemoryThresholdLimit(100);
    apacheHttpClient4Engine.setFileUploadMemoryUnit(ApacheHttpClient4Engine.MemoryUnit.MB);

    ResteasyProviderFactory factory = ResteasyProviderFactory.getInstance();
    ClientHttpEngine httpEngine = apacheHttpClient4Engine;

    ResteasyClient client = new ResteasyClientBuilder().
        httpEngine(httpEngine).
        providerFactory(factory).
        register(GZIPDecodingInterceptor.class).
        register(AcceptEncodingGZIPFilter.class).
        build();
    ResteasyWebTarget target = client.target(server);
    return target.proxy(DataIngestorAPI.class);
  }

  @Override
  public synchronized void connect() throws IllegalStateException, IOException {
    if (httpClient == null) {
      directService = createWavefrontService(server, token);
      scheduledFuture = scheduler.scheduleAtFixedRate(this, 1, 1, TimeUnit.SECONDS);
    }
  }

  @Override
  public void send(String name, double value) throws IOException {
    addPoint(name, value, null, DEFAULT_SOURCE, null);
  }

  @Override
  public void send(String name, double value, @Nullable Long timestamp) throws IOException {
    addPoint(name, value, timestamp, DEFAULT_SOURCE, null);
  }

  @Override
  public void send(String name, double value, @Nullable Long timestamp, String source) throws IOException {
    addPoint(name, value, timestamp, source, null);
  }

  @Override
  public void send(String name, double value, String source, @Nullable Map<String, String> pointTags) throws IOException {
    addPoint(name, value, null, source, pointTags);
  }

  @Override
  public void send(String name, double value, @Nullable Long timestamp, String source,
                   @Nullable Map<String, String> pointTags) throws IOException {
    addPoint(name, value, timestamp, source, pointTags);
  }

  private void addPoint(@NotNull String name, double value, @Nullable Long timestamp, @NotNull String source,
                        @Nullable Map<String, String> pointTags) throws IOException {

    //TODO: extra validation required?
    String point = pointToString(name, value, timestamp, source, pointTags);
    if (point != null) {
      synchronized(pointsMutex) {
        this.buffer.add(point);
      }
    }
  }

  private static String escapeQuotes(String raw) {
    return StringUtils.replace(raw, quote, escapedQuote);
  }

  @Nullable
  static String pointToString(String name, double value, @Nullable Long timestamp, String source,
                               @Nullable Map<String, String> pointTags) {

    if (name == null || name.isEmpty() || source == null || source.isEmpty()) {
      LOGGER.debug("Invalid point: Empty name/source");
      return null;
    }

    if (timestamp == null) {
      timestamp = System.currentTimeMillis()/1000;
    }

    StringBuilder sb = new StringBuilder(quote)
        .append(escapeQuotes(name)).append(quote).append(" ")
        .append(Double.toString(value)).append(" ")
        .append(Long.toString(timestamp)).append(" ")
        .append("source=").append(quote).append(escapeQuotes(source)).append(quote);

    if (pointTags != null) {
      for (Map.Entry<String, String> entry : pointTags.entrySet()) {
        sb.append(' ').append(quote).append(escapeQuotes(entry.getKey())).append(quote)
            .append("=")
            .append(quote).append(escapeQuotes(entry.getValue())).append(quote);
      }
    }
    return sb.toString();
  }

  @Override
  public void flush() throws IOException {
    internalFlush();
  }

  private void internalFlush() throws IOException {

    if (!isConnected()) {
        return;
    }

    List<String> points = getPointsBatch();
    if (points.isEmpty()) {
      return;
    }

    Response response = null;
    try (InputStream is = pointsToStream(points)) {
      response = directService.report("graphite_v2", is);
      if (response.getStatusInfo().getFamily() == Response.Status.Family.SERVER_ERROR ||
          response.getStatusInfo().getFamily() == Response.Status.Family.CLIENT_ERROR) {
        LOGGER.debug("Error reporting points, respStatus=" + response.getStatus());
      }
    } finally {
      if (response != null) {
        // releases the connection for reuse
        response.close();
      }
    }
  }

  private InputStream pointsToStream(List<String> points) {
    StringBuilder sb = new StringBuilder();
    boolean newLine = false;
    for (String point : points) {
      if (newLine) {
        sb.append("\n");
      }
      sb.append(point);
      newLine = true;
    }
    return new ByteArrayInputStream(sb.toString().getBytes());
  }

  private List<String> getPointsBatch() {
    List<String> points;
    synchronized(pointsMutex) {
      int blockSize = Math.min(buffer.size(), 10000);
      points = buffer.subList(0, blockSize);
      buffer = new ArrayList<>(buffer.subList(blockSize, buffer.size()));
    }
    return points;
  }

  @Override
  public synchronized boolean isConnected() {
    return httpClient != null;
  }

  @Override
  public int getFailureCount() {
    return 0;
  }

  @Override
  public synchronized void close() throws IOException {
    if (httpClient != null) {
      scheduledFuture.cancel(false);
      scheduledFuture = null;
      httpClient.close();
      httpClient = null;
      directService = null;
    }
  }

  @Override
  public void run() {
    try {
      this.internalFlush();
    } catch (Exception ex) {
      LOGGER.debug("Unable to report to Wavefront", ex);
    }
  }

  private static final class TokenHeaderHttpRequestExectutor extends HttpRequestExecutor {
    private final String token;

    public TokenHeaderHttpRequestExectutor(String token) {
      this.token = "Bearer " + token;
    }

    @Override
    public HttpResponse execute(final HttpRequest request, final HttpClientConnection conn, final HttpContext context)
        throws IOException, HttpException {
      request.addHeader("Authorization", this.token);
      return super.execute(request, conn, context);
    }
  }
}