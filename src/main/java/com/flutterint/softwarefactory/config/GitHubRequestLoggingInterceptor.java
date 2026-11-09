package com.flutterint.softwarefactory.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Logs exactly how every outbound GitHub request is built, and what came back.
 *
 * <p>This is deliberately verbose — one of the goals of the first slice is to *see* the request.
 * Authorization is redacted so the token never reaches {@code C:\logs\software-factory}.
 */
public class GitHubRequestLoggingInterceptor implements ClientHttpRequestInterceptor {

  private static final Logger log = LogManager.getLogger(GitHubRequestLoggingInterceptor.class);

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {

    log.info("--> {} {}", request.getMethod(), request.getURI());
    request.getHeaders().forEach((name, values) -> log.debug("    {}: {}", name, redact(name, values)));
    if (body.length > 0) {
      log.debug("    body: {}", new String(body, StandardCharsets.UTF_8));
    }

    long start = System.nanoTime();
    ClientHttpResponse response = execution.execute(request, body);
    long ms = (System.nanoTime() - start) / 1_000_000;

    log.info("<-- {} {} ({} ms) for {}", response.getStatusCode().value(),
        response.getStatusText(), ms, request.getURI());

    HttpHeaders rh = response.getHeaders();
    log.debug("    x-ratelimit-remaining: {} | x-oauth-scopes: {}",
        rh.getFirst("x-ratelimit-remaining"), rh.getFirst("x-oauth-scopes"));

    return response;
  }

  private static Object redact(String name, Object values) {
    return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? "Bearer ****redacted****" : values;
  }
}

