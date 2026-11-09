package com.flutterint.softwarefactory.startup;

import com.flutterint.softwarefactory.config.GitHubProperties;
import com.flutterint.softwarefactory.domain.GitHubModel.CommitInfo;
import com.flutterint.softwarefactory.domain.GitHubModel.TokenInfo;
import com.flutterint.softwarefactory.service.GitHubService;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * On boot, proves end-to-end connectivity by printing the last N commits on {@code develop}.
 *
 * <p>Disable with {@code factory.startup.probe-enabled=false}.
 */
@Component
@ConditionalOnProperty(
    prefix = "factory.startup",
    name = "probe-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class StartupProbe implements ApplicationRunner {

  private static final Logger log = LogManager.getLogger(StartupProbe.class);

  private final GitHubService gitHub;
  private final GitHubProperties props;

  public StartupProbe(GitHubService gitHub, GitHubProperties props) {
    this.gitHub = gitHub;
    this.props = props;
  }

  @Override
  public void run(ApplicationArguments args) {
    log.info("================ SOFTWARE FACTORY - GITHUB PROBE ================");
    log.info("target repo   : {}", props.repoUrl());
    log.info("repo slug     : {}", props.repoSlug());
    log.info("base branch   : {}", props.baseBranch());
    try {
      TokenInfo token = gitHub.getTokenInfo();
      log.info("token user    : {} (scopes: {})", token.login(), token.scopes());

      List<CommitInfo> commits = gitHub.getRecentCommitsOnBaseBranch();
      log.info("last {} commits on '{}':", commits.size(), props.baseBranch());
      commits.forEach(
          c -> log.info("  {}  {}  {}  {}", c.shortSha(), c.date(), c.authorName(), c.summary()));
    } catch (RuntimeException e) {
      log.error("GitHub probe FAILED: {}", e.getMessage());
    }
    log.info("=================================================================");
  }
}


