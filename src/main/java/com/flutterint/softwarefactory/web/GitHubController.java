package com.flutterint.softwarefactory.web;

import com.flutterint.softwarefactory.config.GitHubProperties;
import com.flutterint.softwarefactory.domain.GitHubModel.BranchInfo;
import com.flutterint.softwarefactory.domain.GitHubModel.CommitInfo;
import com.flutterint.softwarefactory.domain.GitHubModel.ComparisonInfo;
import com.flutterint.softwarefactory.domain.GitHubModel.PocResult;
import com.flutterint.softwarefactory.domain.GitHubModel.PullRequestInfo;
import com.flutterint.softwarefactory.domain.GitHubModel.RepositoryInfo;
import com.flutterint.softwarefactory.domain.GitHubModel.TokenInfo;
import com.flutterint.softwarefactory.service.GitHubService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only diagnostics API over {@link GitHubService}. */
@RestController
@RequestMapping("/api/github")
@Tag(name = "GitHub", description = "Read-only operations against the target repository")
public class GitHubController {

  private final GitHubService gitHub;
  private final GitHubProperties props;

  public GitHubController(GitHubService gitHub, GitHubProperties props) {
    this.gitHub = gitHub;
    this.props = props;
  }

  /** The effective configuration, so you can see what the app bound. Token is never exposed. */
  @Operation(summary = "Effective configuration", description = "Shows the bound factory.github.* properties. The token itself is never exposed.")
  @GetMapping("/config")
  public Map<String, Object> config() {
    return Map.of(
        "repoUrl", props.repoUrl(),
        "apiBaseUrl", props.apiBaseUrl(),
        "owner", props.owner(),
        "targetApp", props.targetApp(),
        "repoSlug", props.repoSlug(),
        "baseBranch", props.baseBranch(),
        "branchPrefix", props.branchPrefix(),
        "defaultCommitLimit", props.defaultCommitLimit(),
        "tokenConfigured", props.token() != null && !props.token().isBlank());
  }

  /** <b>Primary endpoint:</b> last N commits on {@code develop} (N defaults to 5). */
  @Operation(summary = "Last N commits", description = "Defaults to the last 5 commits on the configured base branch (develop).")
  @GetMapping("/commits")
  public List<CommitInfo> commits(
      @Parameter(description = "Branch/ref; defaults to factory.github.base-branch (develop)")
          @RequestParam(required = false) String branch,
      @Parameter(description = "Number of commits; defaults to factory.github.default-commit-limit (5)")
          @RequestParam(required = false, defaultValue = "0") int limit) {
    return gitHub.getRecentCommits(branch, limit);
  }

  @Operation(summary = "Repository metadata", description = "Includes a warning when GitHub's default_branch differs from the factory's base branch.")
  @GetMapping("/repo")
  public RepositoryInfo repo() {
    return gitHub.getRepositoryInfo();
  }

  @Operation(summary = "List branches", description = "Paginated under the hood — the target repo has ~136 branches.")
  @GetMapping("/branches")
  public List<BranchInfo> branches(
      @RequestParam(required = false, defaultValue = "false") boolean onlyProtected) {
    return onlyProtected ? gitHub.getProtectedBranches() : gitHub.getBranches();
  }

  @Operation(summary = "Compare two refs", description = "Defaults to master...develop.")
  @GetMapping("/compare")
  public ComparisonInfo compare(
      @RequestParam(required = false, defaultValue = "master") String base,
      @RequestParam(required = false) String head) {
    return gitHub.compare(base, head);
  }

  @Operation(summary = "Maven module poms", description = "Every pom.xml under conversionrouterNew/ — the repo has no aggregator POM.")
  @GetMapping("/modules")
  public List<String> mavenModules(@RequestParam(required = false) String ref) {
    return gitHub.findMavenModulePoms(ref);
  }

  @Operation(summary = "Raw file content")
  @GetMapping(value = "/file", produces = MediaType.TEXT_PLAIN_VALUE)
  public String file(
      @RequestParam String path, @RequestParam(required = false) String ref) {
    return gitHub.getFileContent(path, ref);
  }

  @Operation(summary = "List pull requests")
  @GetMapping("/pulls")
  public List<PullRequestInfo> pulls(
      @RequestParam(required = false, defaultValue = "open") String state,
      @RequestParam(required = false) String base,
      @RequestParam(required = false, defaultValue = "30") int limit) {
    return gitHub.getPullRequests(state, base, limit);
  }

  @Operation(summary = "Token identity + scopes")
  @GetMapping("/token")
  public TokenInfo token() {
    return gitHub.getTokenInfo();
  }

  @Operation(
      summary = "v1 Goal #0 — write-path proof of concept",
      description =
          "Creates a feature branch off the base branch, adds a javadoc comment to "
              + "ProcessorService.java, commits it, opens a PR against the base branch and "
              + "requests kolevsta-flutterint as reviewer. Validates branch/commit/PR write access.")
  @PostMapping("/poc/javadoc")
  public PocResult javadocPoc() {
    return gitHub.createJavadocPoc();
  }
}

