package com.flutterint.softwarefactory.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the {@code factory.github.*} block of application.properties.
 *
 * <p>Mirrors the parameters of {@code tools/inspect-target.ps1} so the PowerShell discovery script
 * and this service stay interchangeable.
 */
@Validated
@ConfigurationProperties(prefix = "factory.github")
public record GitHubProperties(

    /** Base URL of the GitHub REST API. */
    @NotBlank String apiBaseUrl,

    /** Web (non-API) URL of the target repository — used for building human-facing links. */
    @NotBlank String repoUrl,

    /** Personal access token. Plaintext for now — see plan.md §6. */
    @NotBlank String token,

    /** Repository owner / organisation. */
    @NotBlank String owner,

    /** Target application == repository name. */
    @NotBlank String targetApp,

    /**
     * The real integration branch. NOTE: GitHub reports {@code master} as the repo's
     * {@code default_branch}, but {@code develop} is the working trunk — never rely on
     * {@code default_branch}. See plan.md §0.1.
     */
    @NotBlank String baseBranch,

    /** Default number of commits returned by the "recent commits" endpoint. */
    @Positive int defaultCommitLimit,

    /** Prefix used for branches the factory creates. */
    String branchPrefix,

    /** Connect/read timeouts in milliseconds. */
    @Positive int connectTimeoutMs,
    @Positive int readTimeoutMs) {

  /** Convenience: {@code FlutterInt/fips-martech-conversion-router}. */
  public String repoSlug() {
    return owner + "/" + targetApp;
  }

  /** Convenience: base path of every repo-scoped API call. */
  public String repoApiPath() {
    return "/repos/" + owner + "/" + targetApp;
  }
}

