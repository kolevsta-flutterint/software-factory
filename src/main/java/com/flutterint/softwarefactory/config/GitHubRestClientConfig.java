package com.flutterint.softwarefactory.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the single {@link RestClient} used for all GitHub REST calls.
 *
 * <p>Equivalent of the {@code $hJson} / {@code $hRaw} header hashtables in
 * {@code tools/inspect-target.ps1}.
 */
@Configuration
public class GitHubRestClientConfig {

  /** Qualifier for the JSON-returning client (Accept: application/vnd.github+json). */
  public static final String GITHUB_JSON = "gitHubJsonRestClient";

  /** Qualifier for the raw-file client (Accept: application/vnd.github.raw). */
  public static final String GITHUB_RAW = "gitHubRawRestClient";

  private static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
  private static final String API_VERSION = "2022-11-28";
  private static final String USER_AGENT = "software-factory";

  @Bean(GITHUB_JSON)
  RestClient gitHubJsonRestClient(GitHubProperties props) {
    return baseBuilder(props, "application/vnd.github+json").build();
  }

  @Bean(GITHUB_RAW)
  RestClient gitHubRawRestClient(GitHubProperties props) {
    return baseBuilder(props, "application/vnd.github.raw").build();
  }

  private RestClient.Builder baseBuilder(GitHubProperties props, String accept) {
    return RestClient.builder()
        .baseUrl(props.apiBaseUrl())
        .requestFactory(requestFactory(props))
        .requestInterceptor(new GitHubRequestLoggingInterceptor())
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.token())
        .defaultHeader(HttpHeaders.ACCEPT, accept)
        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
        .defaultHeader(API_VERSION_HEADER, API_VERSION);
  }

  /** Buffering factory so the logging interceptor can read the body without consuming it. */
  private ClientHttpRequestFactory requestFactory(GitHubProperties props) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(props.connectTimeoutMs()));
    factory.setReadTimeout(Duration.ofMillis(props.readTimeoutMs()));
    return new BufferingClientHttpRequestFactory(factory);
  }
}



