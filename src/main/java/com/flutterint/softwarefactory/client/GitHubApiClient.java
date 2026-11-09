package com.flutterint.softwarefactory.client;

import com.flutterint.softwarefactory.client.dto.GitHubWire;
import com.flutterint.softwarefactory.config.GitHubProperties;
import com.flutterint.softwarefactory.config.GitHubRestClientConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Thin, stateless transport layer over the GitHub REST API.
 *
 * <p>One method per endpoint used by {@code tools/inspect-target.ps1}. No business rules live here
 * — mapping and interpretation belong in the service layer.
 */
@Component
public class GitHubApiClient {

  private static final Logger log = LogManager.getLogger(GitHubApiClient.class);
  private static final int MAX_PER_PAGE = 100;
  private static final int PAGE_SAFETY_LIMIT = 20;

  private final RestClient json;
  private final RestClient raw;
  private final GitHubProperties props;

  public GitHubApiClient(
      @Qualifier(GitHubRestClientConfig.GITHUB_JSON) RestClient json,
      @Qualifier(GitHubRestClientConfig.GITHUB_RAW) RestClient raw,
      GitHubProperties props) {
    this.json = json;
    this.raw = raw;
    this.props = props;
  }

  /** {@code GET /repos/{owner}/{repo}} */
  public GitHubWire.Repository getRepository() {
    String path = props.repoApiPath();
    return get(path, GitHubWire.Repository.class);
  }

  /**
   * {@code GET /repos/{owner}/{repo}/branches} — follows pagination until exhausted.
   *
   * <p>The target repo has ~136 branches, so a single page is not enough.
   */
  public List<GitHubWire.Branch> listAllBranches() {
    List<GitHubWire.Branch> all = new ArrayList<>();
    for (int page = 1; page <= PAGE_SAFETY_LIMIT; page++) {
      final int p = page;
      List<GitHubWire.Branch> batch =
          exchangeList(
              props.repoApiPath() + "/branches",
              uri ->
                  uri.path(props.repoApiPath() + "/branches")
                      .queryParam("per_page", MAX_PER_PAGE)
                      .queryParam("page", p)
                      .build(),
              new ParameterizedTypeReference<List<GitHubWire.Branch>>() {});
      if (batch == null || batch.isEmpty()) {
        break;
      }
      all.addAll(batch);
      if (batch.size() < MAX_PER_PAGE) {
        break;
      }
    }
    log.debug("Fetched {} branches for {}", all.size(), props.repoSlug());
    return all;
  }

  /** {@code GET /repos/{owner}/{repo}/commits?sha={ref}&per_page={limit}} */
  public List<GitHubWire.Commit> listCommits(String ref, int limit) {
    String path = props.repoApiPath() + "/commits";
    return exchangeList(
        path,
        uri ->
            uri.path(path)
                .queryParam("sha", ref)
                .queryParam("per_page", Math.min(limit, MAX_PER_PAGE))
                .build(),
        new ParameterizedTypeReference<List<GitHubWire.Commit>>() {});
  }

  /** {@code GET /repos/{owner}/{repo}/compare/{base}...{head}} */
  public GitHubWire.Comparison compare(String base, String head) {
    String path = props.repoApiPath() + "/compare/" + base + "..." + head;
    return get(path, GitHubWire.Comparison.class);
  }

  /** {@code GET /repos/{owner}/{repo}/git/trees/{ref}?recursive=1} */
  public GitHubWire.Tree getTree(String ref, boolean recursive) {
    String path = props.repoApiPath() + "/git/trees/" + ref;
    return exchange(
        path,
        uri -> {
          uri.path(path);
          if (recursive) {
            uri.queryParam("recursive", 1);
          }
          return uri.build();
        },
        new ParameterizedTypeReference<GitHubWire.Tree>() {},
        json);
  }

  /** {@code GET /repos/{owner}/{repo}/contents/{path}?ref={ref}} with {@code Accept: raw}. */
  public String getRawFile(String filePath, String ref) {
    String path = props.repoApiPath() + "/contents/" + filePath;
    return exchange(
        path,
        uri -> uri.path(path).queryParam("ref", ref).build(),
        new ParameterizedTypeReference<String>() {},
        raw);
  }

  /** {@code GET /repos/{owner}/{repo}/pulls?state={state}&base={base}} */
  public List<GitHubWire.PullRequest> listPullRequests(String state, String base, int limit) {
    String path = props.repoApiPath() + "/pulls";
    return exchangeList(
        path,
        uri -> {
          uri.path(path)
              .queryParam("state", state)
              .queryParam("per_page", Math.min(limit, MAX_PER_PAGE))
              .queryParam("sort", "updated")
              .queryParam("direction", "desc");
          if (base != null && !base.isBlank()) {
            uri.queryParam("base", base);
          }
          return uri.build();
        },
        new ParameterizedTypeReference<List<GitHubWire.PullRequest>>() {});
  }

  /**
   * {@code GET /user} — returns the login plus the {@code x-oauth-scopes} header, i.e. the
   * "=== TOKEN SCOPES ===" block of the PowerShell script.
   */
  public Map<String, String> getAuthenticatedUserAndScopes() {
    String path = "/user";
    try {
      ResponseEntity<Map<String, Object>> response =
          json.get()
              .uri(path)
              .retrieve()
              .toEntity(new ParameterizedTypeReference<Map<String, Object>>() {});
      Object login = response.getBody() == null ? null : response.getBody().get("login");
      return Map.of(
          "login", String.valueOf(login),
          "scopes", header(response, "x-oauth-scopes"),
          "rateLimitRemaining", header(response, "x-ratelimit-remaining"));
    } catch (RestClientException e) {
      throw new GitHubApiException(path, e);
    }
  }

  // ------------------------------------------------------------------ write-path (POC)

  /** {@code GET /repos/{owner}/{repo}/git/ref/heads/{branch}} */
  public GitHubWire.GitRef getBranchRef(String branch) {
    String path = props.repoApiPath() + "/git/ref/heads/" + branch;
    return get(path, GitHubWire.GitRef.class);
  }

  /** {@code POST /repos/{owner}/{repo}/git/refs} — creates a new branch pointing at {@code sha}. */
  public void createBranch(String newBranch, String sha) {
    String path = props.repoApiPath() + "/git/refs";
    GitHubWire.CreateRefRequest body = new GitHubWire.CreateRefRequest("refs/heads/" + newBranch, sha);
    post(path, body, GitHubWire.GitRef.class);
  }

  /**
   * {@code GET /repos/{owner}/{repo}/contents/{path}?ref={ref}} (JSON, not raw) — returns the
   * blob sha + base64 content needed to update the file.
   */
  public GitHubWire.ContentFile getFileMetadata(String filePath, String ref) {
    String path = props.repoApiPath() + "/contents/" + filePath;
    return exchange(
        path,
        uri -> uri.path(path).queryParam("ref", ref).build(),
        new ParameterizedTypeReference<GitHubWire.ContentFile>() {},
        json);
  }

  /** {@code PUT /repos/{owner}/{repo}/contents/{path}} — creates/updates a file on {@code branch}. */
  public GitHubWire.ContentCommitResponse putFileContent(
      String filePath, String message, String base64Content, String sha, String branch) {
    String path = props.repoApiPath() + "/contents/" + filePath;
    GitHubWire.UpdateContentRequest body =
        new GitHubWire.UpdateContentRequest(message, base64Content, sha, branch);
    try {
      return json.put()
          .uri(path)
          .body(body)
          .retrieve()
          .body(GitHubWire.ContentCommitResponse.class);
    } catch (RestClientException e) {
      throw new GitHubApiException(path, e);
    }
  }

  /** {@code POST /repos/{owner}/{repo}/pulls} */
  public GitHubWire.PullRequest createPullRequest(String title, String head, String base, String body) {
    String path = props.repoApiPath() + "/pulls";
    return post(path, new GitHubWire.CreatePullRequestRequest(title, head, base, body), GitHubWire.PullRequest.class);
  }

  /** {@code POST /repos/{owner}/{repo}/pulls/{number}/requested_reviewers} */
  public void requestReviewers(int prNumber, List<String> reviewers) {
    String path = props.repoApiPath() + "/pulls/" + prNumber + "/requested_reviewers";
    post(path, new GitHubWire.RequestReviewersRequest(reviewers), Map.class);
  }

  // ------------------------------------------------------------------ helpers

  private <T> T post(String path, Object body, Class<T> type) {
    try {
      return json.post().uri(path).body(body).retrieve().body(type);
    } catch (RestClientException e) {
      throw new GitHubApiException(path, e);
    }
  }

  private <T> T get(String path, Class<T> type) {
    try {
      return json.get().uri(path).retrieve().body(type);
    } catch (RestClientException e) {
      throw new GitHubApiException(path, e);
    }
  }

  private <T> List<T> exchangeList(
      String logicalPath,
      java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uriFn,
      ParameterizedTypeReference<List<T>> type) {
    List<T> body = exchange(logicalPath, uriFn, type, json);
    return body == null ? List.of() : body;
  }

  private <T> T exchange(
      String logicalPath,
      java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uriFn,
      ParameterizedTypeReference<T> type,
      RestClient client) {
    try {
      return client.get().uri(uriFn::apply).retrieve().body(type);
    } catch (RestClientException e) {
      throw new GitHubApiException(logicalPath, e);
    }
  }

  private static String header(ResponseEntity<?> response, String name) {
    List<String> values = response.getHeaders().get(name);
    return values == null || values.isEmpty() ? "" : String.join(",", values);
  }

  /** Visible for diagnostics: the fully-qualified URL a repo-scoped call would hit. */
  public String describeEndpoint(String suffix) {
    return String.join(
        "", Arrays.asList(props.apiBaseUrl(), props.repoApiPath(), suffix == null ? "" : suffix));
  }
}

