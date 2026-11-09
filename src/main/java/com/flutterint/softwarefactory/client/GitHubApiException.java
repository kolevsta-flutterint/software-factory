package com.flutterint.softwarefactory.client;

import org.springframework.http.HttpStatusCode;

/** Thrown when GitHub returns a non-2xx response or the call cannot be completed. */
public class GitHubApiException extends RuntimeException {

  private final HttpStatusCode status;
  private final String requestPath;

  public GitHubApiException(HttpStatusCode status, String requestPath, String message) {
    super("GitHub API %s for %s — %s".formatted(status, requestPath, message));
    this.status = status;
    this.requestPath = requestPath;
  }

  public GitHubApiException(String requestPath, Throwable cause) {
    super("GitHub API call failed for " + requestPath + " — " + cause.getMessage(), cause);
    this.status = null;
    this.requestPath = requestPath;
  }

  public HttpStatusCode getStatus() {
    return status;
  }

  public String getRequestPath() {
    return requestPath;
  }
}

