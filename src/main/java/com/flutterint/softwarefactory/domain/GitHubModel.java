package com.flutterint.softwarefactory.domain;

import java.time.OffsetDateTime;
import java.util.List;

/** Factory-facing domain model. Stable even if the GitHub wire format changes. */
public final class GitHubModel {

  private GitHubModel() {}

  /** Mirrors the "=== REPO ===" block of inspect-target.ps1. */
  public record RepositoryInfo(
      String fullName,
      boolean isPrivate,
      String language,
      String defaultBranch,
      String integrationBranch,
      long sizeKb,
      OffsetDateTime pushedAt,
      String htmlUrl,
      String warning) {}

  /** Mirrors the "--- branches ---" block. */
  public record BranchInfo(String name, boolean isProtected, String sha, String shortSha) {}

  /** Mirrors the "--- last N commits on {ref} ---" block. */
  public record CommitInfo(
      String sha,
      String shortSha,
      String authorName,
      String authorLogin,
      String authorEmail,
      OffsetDateTime date,
      String summary,
      String fullMessage,
      String htmlUrl) {}

  /** Mirrors the "--- compare master...develop ---" block. */
  public record ComparisonInfo(
      String base,
      String head,
      String status,
      int aheadBy,
      int behindBy,
      int totalCommits,
      int filesChanged) {}

  /** Mirrors the "=== TREE ===" block. */
  public record TreeInfo(String ref, int entryCount, boolean truncated, List<String> paths) {}

  /** Mirrors the "=== OPEN PRs ===" / "=== RECENT CLOSED PRs ===" blocks. */
  public record PullRequestInfo(
      int number,
      String title,
      String state,
      String author,
      String baseRef,
      String headRef,
      OffsetDateTime createdAt,
      OffsetDateTime mergedAt,
      String htmlUrl) {}

  /** Mirrors the "=== TOKEN SCOPES ===" block. */
  public record TokenInfo(String login, String scopes, String rateLimitRemaining) {}

  /** Result of the end-to-end "branch → commit → PR" proof of concept. */
  public record PocResult(
      String branch,
      String filePath,
      String commitSha,
      String commitUrl,
      int pullRequestNumber,
      String pullRequestUrl,
      String reviewer) {}
}

