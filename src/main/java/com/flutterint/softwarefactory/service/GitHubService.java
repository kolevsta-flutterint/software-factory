package com.flutterint.softwarefactory.service;

import com.flutterint.softwarefactory.client.GitHubApiClient;
import com.flutterint.softwarefactory.client.GitHubApiException;
import com.flutterint.softwarefactory.client.dto.GitHubWire;
import com.flutterint.softwarefactory.config.GitHubProperties;
import com.flutterint.softwarefactory.domain.GitHubModel.BranchInfo;
import com.flutterint.softwarefactory.domain.GitHubModel.CommitInfo;
import com.flutterint.softwarefactory.domain.GitHubModel.ComparisonInfo;
import com.flutterint.softwarefactory.domain.GitHubModel.PocResult;
import com.flutterint.softwarefactory.domain.GitHubModel.PullRequestInfo;
import com.flutterint.softwarefactory.domain.GitHubModel.RepositoryInfo;
import com.flutterint.softwarefactory.domain.GitHubModel.TokenInfo;
import com.flutterint.softwarefactory.domain.GitHubModel.TreeInfo;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Factory-facing GitHub operations.
 *
 * <p>This is the Java port of {@code tools/inspect-target.ps1}. Every public method here maps to one
 * of the script's output blocks, so discovery can be re-run from the app instead of PowerShell.
 *
 * <p><b>Branch rule:</b> all defaults resolve to {@code factory.github.base-branch} ({@code
 * develop}). The repo's GitHub {@code default_branch} is {@code master} and is deliberately never
 * used as a default — see plan.md §0.1.
 */
@Service
public class GitHubService {

  private static final Logger log = LogManager.getLogger(GitHubService.class);
  private static final int SHORT_SHA_LENGTH = 8;

  private final GitHubApiClient client;
  private final GitHubProperties props;

  public GitHubService(GitHubApiClient client, GitHubProperties props) {
    this.client = client;
    this.props = props;
  }

  // ----------------------------------------------------------- primary use case

  /** The last N commits on the configured integration branch ({@code develop}). */
  public List<CommitInfo> getRecentCommitsOnBaseBranch() {
    return getRecentCommits(props.baseBranch(), props.defaultCommitLimit());
  }

  /** The last {@code limit} commits on {@code ref}. */
  public List<CommitInfo> getRecentCommits(String ref, int limit) {
    String branch = resolveRef(ref);
    int n = limit > 0 ? limit : props.defaultCommitLimit();
    log.info("Fetching last {} commits on '{}' of {}", n, branch, props.repoSlug());

    return client.listCommits(branch, n).stream().limit(n).map(GitHubService::toCommitInfo).toList();
  }

  // ----------------------------------------------------------- discovery blocks

  /** "=== REPO ===" — includes an explicit warning when default_branch != integration branch. */
  public RepositoryInfo getRepositoryInfo() {
    GitHubWire.Repository r = client.getRepository();
    String warning =
        props.baseBranch().equals(r.defaultBranch())
            ? null
            : "GitHub default_branch is '%s' but the factory integrates against '%s'. "
                    .formatted(r.defaultBranch(), props.baseBranch())
                + "Never open a PR without an explicit base.";
    if (warning != null) {
      log.warn(warning);
    }
    return new RepositoryInfo(
        r.fullName(),
        r.isPrivate(),
        r.language(),
        r.defaultBranch(),
        props.baseBranch(),
        r.size(),
        r.pushedAt(),
        r.htmlUrl(),
        warning);
  }

  /** "--- branches ---", sorted by name. */
  public List<BranchInfo> getBranches() {
    return client.listAllBranches().stream()
        .map(
            b ->
                new BranchInfo(
                    b.name(),
                    b.isProtected(),
                    b.commit() == null ? null : b.commit().sha(),
                    shortSha(b.commit() == null ? null : b.commit().sha())))
        .sorted(Comparator.comparing(BranchInfo::name))
        .toList();
  }

  /** Only the protected branches — useful for the "never push here" guard. */
  public List<BranchInfo> getProtectedBranches() {
    return getBranches().stream().filter(BranchInfo::isProtected).toList();
  }

  /** "--- compare base...head ---". Defaults to {@code master...develop}. */
  public ComparisonInfo compare(String base, String head) {
    String b = (base == null || base.isBlank()) ? "master" : base;
    String h = resolveRef(head);
    GitHubWire.Comparison c = client.compare(b, h);
    return new ComparisonInfo(
        b,
        h,
        c.status(),
        c.aheadBy(),
        c.behindBy(),
        c.totalCommits(),
        c.files() == null ? 0 : c.files().size());
  }

  /** "=== TREE ===" — the full recursive file listing at {@code ref}. */
  public TreeInfo getTree(String ref) {
    String branch = resolveRef(ref);
    GitHubWire.Tree t = client.getTree(branch, true);
    List<String> paths =
        t.tree() == null ? List.of() : t.tree().stream().map(GitHubWire.TreeEntry::path).toList();
    return new TreeInfo(branch, paths.size(), t.truncated(), paths);
  }

  /**
   * Every Maven module in the target monorepo. The repo has no aggregator POM, so each {@code
   * pom.xml} is an independent build unit — see plan.md §0.6.
   */
  public List<String> findMavenModulePoms(String ref) {
    return getTree(ref).paths().stream().filter(p -> p.endsWith("pom.xml")).sorted().toList();
  }

  /** Raw file content at {@code ref} — the Java equivalent of {@code Get-Raw}. */
  public String getFileContent(String path, String ref) {
    return client.getRawFile(path, resolveRef(ref));
  }

  /** "=== OPEN PRs ===" — defaults to open PRs targeting the integration branch. */
  public List<PullRequestInfo> getPullRequests(String state, String base, int limit) {
    String s = (state == null || state.isBlank()) ? "open" : state;
    int n = limit > 0 ? limit : 30;
    return client.listPullRequests(s, base, n).stream().map(GitHubService::toPullRequestInfo).toList();
  }

  /** "=== TOKEN SCOPES ===" — confirms the PAT is valid and shows what it can do. */
  public TokenInfo getTokenInfo() {
    Map<String, String> m = client.getAuthenticatedUserAndScopes();
    return new TokenInfo(m.get("login"), m.get("scopes"), m.get("rateLimitRemaining"));
  }

  // ----------------------------------------------------------- proof of concept (write path)

  private static final String POC_FILE_PATH =
      "conversionrouterNew/conversionrouter.tradedesk-dispatcher/src/main/java/com/starsgroup/"
          + "martech/conversionrouter/tradedesk/service/ProcessorService.java";
  private static final String POC_REVIEWER = "kolevsta-flutterint";
  private static final Pattern CLASS_DECL_PATTERN =
      Pattern.compile("(?m)^(\\s*)((?:public\\s+|final\\s+|abstract\\s+)*class\\s+\\w+)");

  /**
   * End-to-end v1 Goal #0 proof of concept: create a feature branch off {@code develop}, add a
   * javadoc comment to {@code ProcessorService.java}, commit it, and open a PR with {@code
   * kolevsta-flutterint} requested as reviewer — so we can validate GitHub write access + PR flow
   * works end to end before building the real pipeline.
   */
  public PocResult createJavadocPoc() {
    String base = props.baseBranch();
    String branch = props.branchPrefix() + "poc-javadoc-" + Instant.now().getEpochSecond();

    log.info("POC: creating branch '{}' off '{}' in {}", branch, base, props.repoSlug());
    String baseSha = client.getBranchRef(base).object().sha();
    client.createBranch(branch, baseSha);

    GitHubWire.ContentFile existing = client.getFileMetadata(POC_FILE_PATH, branch);
    String original = decodeContent(existing);
    String updated = insertJavadoc(original);
    if (updated.equals(original)) {
      throw new IllegalStateException(
          "Could not locate a class declaration in " + POC_FILE_PATH + " to attach the javadoc to.");
    }

    String commitMessage = "poc: add javadoc comment to ProcessorService (software-factory PR validation)";
    GitHubWire.ContentCommitResponse commit =
        client.putFileContent(
            POC_FILE_PATH,
            commitMessage,
            Base64.getEncoder().encodeToString(updated.getBytes(StandardCharsets.UTF_8)),
            existing.sha(),
            branch);

    String prBody =
        "Automated proof of concept from `software-factory`.\n\n"
            + "Validates that the factory can create a branch, commit a change, and open a PR "
            + "against `"
            + base
            + "` end to end. No functional change — a javadoc comment was added to "
            + "`ProcessorService`.\n\n"
            + "@" + POC_REVIEWER + " please review.";
    GitHubWire.PullRequest pr =
        client.createPullRequest(
            "POC: software-factory write-path validation", branch, base, prBody);
    log.info("POC: opened PR #{} ({})", pr.number(), pr.htmlUrl());

    String reviewerResult = POC_REVIEWER;
    try {
      client.requestReviewers(pr.number(), List.of(POC_REVIEWER));
    } catch (GitHubApiException e) {
      // Known GitHub constraint: a PR author cannot be requested as reviewer on their own PR.
      // Since the configured PAT belongs to kolevsta-flutterint, this always fails when that is
      // also the requested reviewer — a real blocker for v1 (needs a distinct bot/service account).
      log.warn("POC: could not request reviewer '{}' — {}", POC_REVIEWER, e.getMessage());
      reviewerResult = "NOT_REQUESTED (" + e.getMessage() + ")";
    }

    return new PocResult(
        branch,
        POC_FILE_PATH,
        commit.commit() == null ? null : commit.commit().sha(),
        commit.content() == null ? null : commit.content().htmlUrl(),
        pr.number(),
        pr.htmlUrl(),
        reviewerResult);
  }

  private static String decodeContent(GitHubWire.ContentFile file) {
    String raw = file.content() == null ? "" : file.content().replace("\n", "");
    return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
  }

  /**
   * Inserts a javadoc comment immediately above the first top-level class declaration, in front of
   * any annotations (e.g. {@code @Service}, {@code @Slf4j}) attached to it.
   */
  private static String insertJavadoc(String source) {
    Matcher m = CLASS_DECL_PATTERN.matcher(source);
    if (!m.find()) {
      return source;
    }
    int insertAt = m.start(1);
    // Walk back over any contiguous "@Annotation" lines directly above the class declaration.
    Matcher ann = Pattern.compile("(?m)^[ \\t]*@\\w+[^\\n]*\\n").matcher(source);
    int lineStart = insertAt;
    while (true) {
      int prevLineStart = source.lastIndexOf('\n', lineStart - 2) + 1;
      if (prevLineStart < 0 || prevLineStart >= lineStart) {
        break;
      }
      String candidate = source.substring(prevLineStart, lineStart);
      if (candidate.strip().startsWith("@")) {
        lineStart = prevLineStart;
      } else {
        break;
      }
    }
    insertAt = lineStart;

    String indent = m.group(1);
    String javadoc =
        indent
            + "/**\n"
            + indent
            + " * Proof-of-concept marker added by the software-factory: validates that the\n"
            + indent
            + " * factory can branch, commit, and open a pull request end to end.\n"
            + indent
            + " */\n";
    return source.substring(0, insertAt) + javadoc + source.substring(insertAt);
  }

  // ----------------------------------------------------------- mapping helpers

  private String resolveRef(String ref) {
    return (ref == null || ref.isBlank()) ? props.baseBranch() : ref;
  }

  private static CommitInfo toCommitInfo(GitHubWire.Commit c) {
    GitHubWire.CommitDetail d = c.commit();
    GitHubWire.GitActor a = d == null ? null : d.author();
    String message = d == null || d.message() == null ? "" : d.message();
    return new CommitInfo(
        c.sha(),
        shortSha(c.sha()),
        a == null ? null : a.name(),
        c.author() == null ? null : c.author().login(),
        a == null ? null : a.email(),
        a == null ? null : a.date(),
        firstLine(message),
        message,
        c.htmlUrl());
  }

  private static PullRequestInfo toPullRequestInfo(GitHubWire.PullRequest p) {
    return new PullRequestInfo(
        p.number(),
        p.title(),
        p.state(),
        p.user() == null ? null : p.user().login(),
        p.base() == null ? null : p.base().ref(),
        p.head() == null ? null : p.head().ref(),
        p.createdAt(),
        p.mergedAt(),
        p.htmlUrl());
  }

  private static String firstLine(String message) {
    int i = message.indexOf('\n');
    return (i < 0 ? message : message.substring(0, i)).strip();
  }

  private static String shortSha(String sha) {
    if (sha == null) {
      return null;
    }
    return sha.length() <= SHORT_SHA_LENGTH ? sha : sha.substring(0, SHORT_SHA_LENGTH);
  }
}

