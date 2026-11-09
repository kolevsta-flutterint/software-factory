package com.flutterint.softwarefactory.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Raw GitHub REST wire-format records. Deliberately partial — only the fields the factory needs.
 *
 * <p>Kept separate from the domain model so a GitHub API change never leaks past the client layer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class GitHubWire {

  private GitHubWire() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Repository(
      @JsonProperty("full_name") String fullName,
      @JsonProperty("private") boolean isPrivate,
      String language,
      @JsonProperty("default_branch") String defaultBranch,
      long size,
      @JsonProperty("pushed_at") OffsetDateTime pushedAt,
      @JsonProperty("html_url") String htmlUrl) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Branch(
      String name,
      @JsonProperty("protected") boolean isProtected,
      CommitRef commit) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CommitRef(String sha, String url) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Commit(
      String sha,
      @JsonProperty("html_url") String htmlUrl,
      CommitDetail commit,
      Author author) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CommitDetail(String message, GitActor author, GitActor committer) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GitActor(String name, String email, OffsetDateTime date) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Author(String login, @JsonProperty("avatar_url") String avatarUrl) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Comparison(
      String status,
      @JsonProperty("ahead_by") int aheadBy,
      @JsonProperty("behind_by") int behindBy,
      @JsonProperty("total_commits") int totalCommits,
      List<FileChange> files) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FileChange(String filename, String status, int additions, int deletions) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Tree(String sha, boolean truncated, List<TreeEntry> tree) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TreeEntry(String path, String type, String sha, Long size) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PullRequest(
      int number,
      String title,
      String state,
      Author user,
      Ref base,
      Ref head,
      @JsonProperty("html_url") String htmlUrl,
      @JsonProperty("created_at") OffsetDateTime createdAt,
      @JsonProperty("merged_at") OffsetDateTime mergedAt) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Ref(String ref, String sha) {}

  // ------------------------------------------------------------ write-path (POC)

  /** {@code GET /git/ref/heads/{branch}} response. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GitRef(String ref, @JsonProperty("object") GitRefObject object) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GitRefObject(String sha, String type) {}

  /** {@code POST /git/refs} request body. */
  public record CreateRefRequest(String ref, String sha) {}

  /** {@code GET /repos/{owner}/{repo}/contents/{path}} (JSON, not raw) response/sha carrier. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ContentFile(
      String sha, String content, String encoding, String path, @JsonProperty("html_url") String htmlUrl) {}

  /** {@code PUT /repos/{owner}/{repo}/contents/{path}} request body. */
  public record UpdateContentRequest(String message, String content, String sha, String branch) {}

  /** {@code PUT .../contents/{path}} response: new content + commit. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ContentCommitResponse(ContentFile content, CommitRef commit) {}

  /** {@code POST /repos/{owner}/{repo}/pulls} request body. */
  public record CreatePullRequestRequest(String title, String head, String base, String body) {}

  /** {@code POST /repos/{owner}/{repo}/pulls/{number}/requested_reviewers} request body. */
  public record RequestReviewersRequest(List<String> reviewers) {}
}

