# Software Factory — Project State Summary
*(as of 2026-11-09, for resuming in a fresh conversation)*

## 1. What this project is

An **autonomous engineering operations system** ("Software Factory") being built to operate
against a separate target repository: **`FlutterInt/fips-martech-conversion-router`**
(private, GitHub). v1 goal: detect Snyk dependency vulnerabilities in the target repo, bump
versions safely, verify with a build, and open a PR — all autonomously. Full design doc:
**`docs/plan.md`** (very large, ~900 lines — read this first in a new session).

Current session only built the **first vertical slice**: a Spring Boot app with a GitHub
read-only integration (list commits/branches/PRs/files on the target repo). No Snyk, no DB,
no remediation logic yet — that's all still ahead.

---

## 2. Repository / workspace layout

```
C:\Dev\software-factory\          <- this project's own git repo (origin/main, 1 commit so far: "Initial commit")
  docs/plan.md                    <- FULL DESIGN DOC (moved from root plan.md -> docs/plan.md)
  docs/project-state-summary.md   <- this file
  README.md                       <- quick-start + architecture summary (kept in sync each step)
  pom.xml
  src/main/java/com/flutterint/softwarefactory/
    SoftwareFactoryApplication.java
    client/
      GitHubApiClient.java        <- one method per GitHub REST endpoint, no business logic
      GitHubApiException.java
      dto/GitHubWire.java         <- GitHub wire-format records (partial, @JsonIgnoreProperties)
    domain/
      GitHubModel.java            <- stable factory-facing model (records), decoupled from wire DTOs
    config/
      GitHubProperties.java       <- @ConfigurationProperties(prefix="factory.github"), a record
      GitHubRestClientConfig.java <- builds 2 RestClient beans (JSON + raw), adds interceptor
      GitHubRequestLoggingInterceptor.java <- logs every outbound call, redacts Authorization
      OpenApiConfig.java          <- Swagger/OpenAPI metadata bean
    service/
      GitHubService.java          <- business/orchestration layer; Java port of tools/inspect-target.ps1
    web/
      GitHubController.java       <- REST façade, @Tag/@Operation annotated for Swagger
      GlobalExceptionHandler.java <- @RestControllerAdvice, maps GitHubApiException -> 502 JSON
    startup/
      StartupProbe.java           <- ApplicationRunner; prints last 5 commits on boot (toggleable)
  src/main/resources/
    application.properties        <- ALL config incl. plaintext GitHub token (see §6 below)
    log4j2-spring.xml             <- Log4j2 config, logs to C:\logs\software-factory
  tools/
    inspect-target.ps1            <- standalone PowerShell discovery script (kept as fallback/reference)
    discovery-develop.txt, extra-develop.txt <- saved discovery output (gitignored)
  target/                         <- build output (gitignored)
```

**Target repo discovery artifacts live in `docs/plan.md` §0** — extensive reverse-engineered
facts about `fips-martech-conversion-router` (16 Maven modules, no aggregator POM, Spring Boot
version split across 4.1.0/4.0.x/3.5.x, branch model, test coverage per module, etc.). This is
reference material, not something this app queries yet (except ad hoc via the controller).

---

## 3. Architecture (strict layering)

```
web  →  service  →  client  →  GitHub REST API
```

- **web**: Spring MVC `@RestController`, maps HTTP params, no business logic.
- **service** (`GitHubService`): all business rules (branch-default-mismatch warning, ref
  resolution to `develop`, commit/PR/tree shaping). This is the reusable, extensible layer —
  explicitly designed per user request as "the functions we can reuse and update."
- **client** (`GitHubApiClient`): thin, stateless, one method per GitHub endpoint
  (`getRepository`, `listAllBranches` w/ pagination, `listCommits`, `compare`, `getTree`,
  `getRawFile`, `listPullRequests`, `getAuthenticatedUserAndScopes`). Wire DTOs
  (`client/dto/GitHubWire`) **never escape this layer** — mapped to `domain/GitHubModel`
  records in the service.
- **config**: two named `RestClient` beans (`gitHubJsonRestClient`, `gitHubRawRestClient`),
  both get the logging interceptor + redacted-token header + `X-GitHub-Api-Version` header.

Design principle carried over from `plan.md`: an `EcosystemAdapter`-style separation — the
GitHub transport is isolated so Snyk/Maven/etc. layers can be added the same way later.

---

## 4. Technology stack (as currently implemented)

| | Decision |
|---|---|
| Language | **Java 21** (downgraded from an initial Java 25 attempt — see §7 issue log) |
| Framework | **Spring Boot 4.1.0** (Spring Framework 7) — note: this is *ahead* of the target repo's own mixed 3.5.x/4.0.x/4.1.0 modules |
| Build | Maven (`pom.xml`, `maven.compiler.release=21`) |
| HTTP client | Spring `RestClient` (not `RestTemplate`/`WebClient`) |
| Logging | **Log4j2**, Logback explicitly excluded from *every* starter (`web`, `validation`, `actuator`, `test`) — this exclusion had to be added to all four, not just `starter-web`, or SLF4J bound to Logback and `log4j2-spring.xml` was ignored |
| API docs | **springdoc-openapi-starter-webmvc-ui 2.8.5** — confirmed working on Boot 4.1.0/Framework 7 despite historically targeting Boot 3 |
| Config | Plain `application.properties`, binding via a Java **record** `@ConfigurationProperties` (`GitHubProperties`) + `@Validated` (Jakarta `@NotBlank`/`@Positive`) |
| Database | **None yet** — no JPA, no Postgres, no Flyway added to this app yet (planned, not implemented) |
| Tests | **None yet** — no `src/test/java` exists |

---

## 5. Implemented functionality

### REST API (`/api/github/*`), all GET, all read-only:

| Path | Purpose |
|---|---|
| `/api/github/commits?branch=&limit=` | **Primary requested feature**: last N commits (default branch=`develop`, default limit=5) |
| `/api/github/config` | effective bound config; token presence boolean only, never the value |
| `/api/github/repo` | repo metadata; emits a `warning` field when GitHub's `default_branch` (`master`) ≠ factory's `baseBranch` (`develop`) |
| `/api/github/branches?onlyProtected=` | all branches, fully paginated (repo has ~136) |
| `/api/github/compare?base=master&head=develop` | ahead/behind/files-changed |
| `/api/github/modules?ref=` | all `pom.xml` paths (there's no aggregator POM in target repo — 16 independent modules) |
| `/api/github/file?path=&ref=` | raw file content via `Accept: application/vnd.github.raw` |
| `/api/github/pulls?state=&base=&limit=` | PR listing |
| `/api/github/token` | authenticated user + OAuth scopes (proves token validity) |
| `/actuator/health`, `/actuator/info`, `/actuator/metrics` | exposed |
| `/swagger-ui.html`, `/v3/api-docs` | Swagger UI + OpenAPI JSON — verified serving real content (1.45MB bundle.js etc.) |

### Startup probe
`StartupProbe` (an `ApplicationRunner`) runs on boot, calls `/user` then lists last 5 commits
on `develop`, logs everything — proves end-to-end connectivity without needing to call the
HTTP API manually. Toggle: `factory.startup.probe-enabled=false`.

### Verified end-to-end (manually, via packaged jar + curl/Invoke-WebRequest)
- App boots on Java 21, Spring Boot 4.1.0.
- Real GitHub calls succeed; e.g. last 5 commits on `develop` returned correctly with shas,
  authors, dates, messages.
- Request/response logging works, with `Authorization` header redacted as
  `Bearer ****redacted****`.
- Log4j2 writes to `C:\logs\software-factory\software-factory.log` and
  `factory-audit.log` (daily rolling, configured but audit logger not yet used by any code).
- Swagger UI and OpenAPI JSON both serve correctly with all 9 endpoints documented.

---

## 6. Important decisions / conventions

1. **`develop` is the integration branch, NOT `master`.** GitHub reports
   `default_branch=master` but it's stale (+52/-10 vs develop, 218 files diff). All defaults in
   code resolve to `develop` (`GitHubProperties.baseBranch()`), and `GitHubService` emits an
   explicit warning if they ever diverge. **Never trust `default_branch` from the API.**
2. **Secrets: token now comes from the `GITHUB_TOKEN` environment variable, not the repo.**
   `application.properties` binds `factory.github.token=${GITHUB_TOKEN}` — nothing secret is
   committed. Set it before running, e.g. `$env:GITHUB_TOKEN = "ghp_..."` (PowerShell) or via the
   IDE run configuration's environment variables. The PAT that was previously hardcoded in this
   file **has been pasted in chat multiple times and must be revoked** at
   https://github.com/settings/tokens regardless.
3. **Java 21, not 25.** First scaffold used Java 25 (JDK was available locally at
   `C:\Programs\zulu25.36.15-ca-jdk25.0.4-win_x64`) but was explicitly downgraded to 21 by user
   request, matching the target repo's own `java.version=21` — rationale documented in
   `docs/plan.md`: one shared toolchain, the factory can literally run the target's own
   `mvn clean verify` with its own JDK.
4. **Spring Boot 4.1.0 chosen for the factory itself** even though the target repo is mid-
   migration across 3.5.4 → 4.0.x → 4.1.0. This is intentional — newest available at start time.
5. **Logback must be excluded from every starter**, not just `spring-boot-starter-web`. This
   was a real bug hit and fixed: actuator/validation pull it back in transitively.
6. **`ClientHttpRequestFactoryBuilder`/`ClientHttpRequestFactorySettings` (Boot 3.x API) don't
   exist in Boot 4.** Replaced with plain `SimpleClientHttpRequestFactory` wrapped in
   `BufferingClientHttpRequestFactory` (buffering needed so the logging interceptor can read
   the body without consuming the stream).
7. **`tools/inspect-target.ps1` is kept** as a zero-dependency PowerShell fallback/reference;
   `GitHubService` is the "real", reusable, updatable implementation the user asked for. A
   mapping table (script block → Java method) is documented in the README.
8. **Wire format vs domain model strictly separated**: `client/dto/GitHubWire` (raw GitHub
   JSON shape, `@JsonIgnoreProperties(ignoreUnknown = true)`) is mapped to `domain/GitHubModel`
   records inside `GitHubService` — a GitHub API change can never leak past the client layer.
9. **Target repo facts are extensively documented in `docs/plan.md` §0** (not re-derived by
   this app): 16 Maven modules under `conversionrouterNew/`, no aggregator POM, mixed Spring
   Boot versions (10 modules on 4.1.0, 1 on 4.0.8, 2 on 4.0.7, 1 on 3.5.14, 2 still on 3.5.4),
   all Java 21, CODEOWNERS = 9 people (2 most active: `dudinart-flutterint`,
   `kolevsta-flutterint`), CI is Jenkins (not GitHub Actions), test coverage is thin and uneven
   per module (0–10 test files), team already does manual Snyk remediation via dedicated
   feature branches (`feature/MAP-*-snyk-vulnerabilities`), and the house style for fixing
   transitive CVEs is `<dependencyManagement>` BOM-import overrides with CVE comments (example
   preserved from `conversionrouter.parsing-service/pom.xml`).

---

## 7. Database structure

**Not implemented yet.** `docs/plan.md` §7 specifies the intended schema (PostgreSQL + Flyway +
Spring Data JPA) for later phases:
- `finding` — one row per Snyk vulnerability (snykIssueId, cve, severity, package, versions, status enum: OPEN/IN_PROGRESS/PR_OPEN/FIXED/IGNORED/NEEDS_HUMAN/NOT_FIXABLE)
- `remediation_attempt` — per fix attempt (strategy enum: DIRECT_BUMP/ANCESTOR_BUMP/PIN/OVERRIDE/AGENT, build/test result, outcome enum)
- `pull_request` — tracks factory-opened PRs (state, checks, merge time)
- `factory_run` — one row per scheduled/manual run
- `policy.yaml` — NOT a DB table, a classpath YAML bound via `@ConfigurationProperties`

None of this exists in code yet — no entities, no repositories, no `pom.xml` JPA/Postgres/
Flyway dependencies, no migrations folder.

---

## 8. Current known issues / loose ends

1. **🔴 Secret exposure**: the real GitHub PAT is committed in `application.properties`
   (currently staged, not yet pushed based on git status — only "Initial commit" exists on
   `origin/main`, and the GitHub-slice files are all staged/modified but not committed again
   yet). **Revoke and rotate the token before pushing**, then move it to
   `application-local.properties` (gitignored) or an env var.
2. **Stray `app-out.log` file at repo root** (`C:\Dev\software-factory\app-out.log`) — leftover
   from manual testing, not cleaned up (it IS covered by the `*.log` gitignore rule, just
   physically present — delete for tidiness).
3. **IDE stale-index false positives**: `get_errors` tool reported "Cannot resolve symbol
   'swagger'" etc. right after adding springdoc — these are NOT real; `mvn clean package`
   compiles cleanly every time. If seen again in a fresh session, re-run a real Maven build
   before trusting IDE-reported errors.
4. **No tests at all.** `spring-boot-starter-test` is a dependency but `src/test/java` doesn't
   exist.
5. **`GitHubController.compare()` defaults `base` to `"master"` hardcoded** rather than reading
   from `GitHubProperties` — minor inconsistency (the rest of the app treats `develop` as the
   one true default).
6. **No persistence, no Snyk integration, no remediation logic at all yet** — this session was
   scoped to "prove GitHub connectivity + layered architecture", nothing more.
7. **Git working tree currently has everything staged-but-uncommitted** (saw via `git status`:
   lots of `new file:` and `modified:` entries after the "Initial commit"). Needs a commit
   (after secret rotation!) to actually save this work into version control history.
8. Branch protection rules on `develop`, Snyk org/project IDs, and whether Jenkins has a
   PR-validation job are all still **unanswered open questions** blocking later phases (listed
   in `docs/plan.md` §0.11 and §11).

---

## 9. Remaining tasks (in planned order, per `docs/plan.md` §12)

1. **Revoke & rotate the GitHub PAT** (urgent, independent of everything else).
2. Commit the current working tree properly (git add/commit) once secrets are fixed.
3. Get **Snyk org ID / project ID(s) / API token** — the one remaining real blocker for the
   core feature.
4. Mine the team's existing manual remediation branches
   (`feature/MAP-27385-snyk-vulnerabilities-tradedesk-service` etc.) for PR-style conventions.
5. Build `SnykCliClient` (invoke `snyk test --all-projects --detection-depth=4 --json` via
   `ProcessBuilder`) → `FindingNormalizer` → persist via a new `ModuleRegistry` that maps
   Snyk's `manifestPath` to one of the target repo's 16 Maven modules.
6. Add PostgreSQL + Flyway + Spring Data JPA to `pom.xml`; write `V1__init.sql` per the §7
   schema above.
7. Build `PolicyEngine` + `VersionResolver` (patch/minor only initially; deny-list modules with
   0–1 test files: `enrichment`, `datastore`, `parsing-service`; deny-list `aep-dispatcher` and
   `braze-dispatcher` until their Boot 3.5.4→4.x migration finishes).
8. Build `MavenAdapter`: must support (a) inline `<version>` edits, (b) `${property}`
   indirection, (c) `<dependencyManagement>` BOM-import overrides with CVE comments — this
   is the team's established house style, not optional.
9. Build `BuildRunner` (runs the target module's own `mvn clean verify`, baseline-before-edit
   to avoid blaming pre-existing Spotless/test failures on the bump) + `RescanVerifier`.
10. Build `RepoCheckoutService` (JGit, shallow clone `--branch develop`) +
    `PullRequestService` (base branch **must be explicitly `develop`**, never rely on
    `default_branch`) + `PrBodyBuilder`.
11. Wire it all into `FactoryRunService`/`FactoryScheduler`, keep `dry-run=true` until proven.
12. Only then: webhooks, agentic repair fallback, multi-repo support (all Phase 2+, see
    `docs/plan.md` §10).

---

## 10. How to resume quickly

```powershell
cd C:\Dev\software-factory
mvn -B -ntp clean package -DskipTests
java -jar target\software-factory-0.0.1-SNAPSHOT.jar
# then browse http://localhost:8080/swagger-ui.html
```

Read `docs/plan.md` in full for target-repo facts and the remediation design; read this
summary for what actually exists in code today. The two documents are complementary —
`plan.md` is the long-term design/discovery doc, this summary is the "what's actually built"
snapshot.

