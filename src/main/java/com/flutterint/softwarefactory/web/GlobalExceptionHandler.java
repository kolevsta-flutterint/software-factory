package com.flutterint.softwarefactory.web;

import com.flutterint.softwarefactory.client.GitHubApiException;
import java.time.OffsetDateTime;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns client failures into a readable JSON body instead of a stack trace. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LogManager.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(GitHubApiException.class)
  public ResponseEntity<Map<String, Object>> handleGitHub(GitHubApiException ex) {
    log.error("GitHub call failed: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(
            Map.of(
                "timestamp", OffsetDateTime.now().toString(),
                "error", "GITHUB_API_ERROR",
                "path", String.valueOf(ex.getRequestPath()),
                "message", String.valueOf(ex.getMessage())));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
    log.error("Unhandled error", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            Map.of(
                "timestamp", OffsetDateTime.now().toString(),
                "error", ex.getClass().getSimpleName(),
                "message", String.valueOf(ex.getMessage())));
  }
}

