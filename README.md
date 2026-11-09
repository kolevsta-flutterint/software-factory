# software-factory

Software factory for the Conversion router — an autonomous engineering operations system.
See [`docs/plan.md`](docs/plan.md) for the full design.

**Target repository:** `FlutterInt/fips-martech-conversion-router`
**Integration branch:** `develop` (GitHub's `default_branch` is the stale `master` — never rely on it)

---

## Stack

| | |
|---|---|
| Java | **21** (LTS) — matches the target repo's toolchain |
| Framework | **Spring Boot 4.1.0** (Spring Framework 7) |
| Build | Maven 3.9+ |
| Logging | Log4j2 → `C:\logs\software-factory`, daily rolling (Logback excluded from every starter) |
| HTTP client | `RestClient` + a logging interceptor |

## Run

```powershell
mvn -B -ntp clean package -DskipTests
java -jar target\software-factory-0.0.1-SNAPSHOT.jar
```

On startup a probe prints the last 5 commits on `develop`
(disable with `factory.startup.probe-enabled=false`).

## Swagger / OpenAPI

- UI:   http://localhost:8080/swagger-ui.html
- JSON: http://localhost:8080/v3/api-docs

Powered by `springdoc-openapi-starter-webmvc-ui`; metadata lives in
`config/OpenApiConfig`, per-endpoint summaries are on `GitHubController`.

## Layers

```
web/        GitHubController            REST facade + GlobalExceptionHandler
service/    GitHubService               factory-facing operations (Java port of inspect-target.ps1)
client/     GitHubApiClient             one method per GitHub endpoint, no business rules
            dto/GitHubWire              GitHub wire format (partial records)
domain/     GitHubModel                 stable factory-facing model
config/     GitHubProperties            binds factory.github.*
            GitHubRestClientConfig      JSON + raw RestClient beans
            GitHubRequestLoggingInterceptor
            OpenApiConfig               Swagger / OpenAPI metadata
startup/    StartupProbe                boot-time connectivity check
```

Dependency direction is strictly `web -> service -> client -> GitHub`.
The wire DTOs never escape the client layer.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/api/github/commits?branch=&limit=` | **last N commits**, defaults `develop` / 5 |
| GET | `/api/github/config` | effective bound config (token never exposed) |
| GET | `/api/github/repo` | repo metadata + default-branch mismatch warning |
| GET | `/api/github/branches?onlyProtected=` | all 136 branches, paginated |
| GET | `/api/github/compare?base=master&head=develop` | divergence |
| GET | `/api/github/modules?ref=` | the 16 `pom.xml` paths (no aggregator POM) |
| GET | `/api/github/file?path=&ref=` | raw file content |
| GET | `/api/github/pulls?state=&base=&limit=` | pull requests |
| GET | `/api/github/token` | token login + scopes |
| GET | `/actuator/health` | |

### Example

```powershell
Invoke-RestMethod 'http://localhost:8080/api/github/commits' |
  Select-Object shortSha, date, authorName, summary | Format-Table -AutoSize
```

## Seeing the request

Every outbound call is logged by `GitHubRequestLoggingInterceptor` (Authorization redacted):

```
--> GET https://api.github.com/repos/FlutterInt/fips-martech-conversion-router/commits?sha=develop&per_page=5
    Authorization: Bearer ****redacted****
    Accept: [application/vnd.github+json]
    User-Agent: [software-factory]
    X-GitHub-Api-Version: [2022-11-28]
<-- 200 OK (402 ms) for .../commits?sha=develop&per_page=5
    x-ratelimit-remaining: 4877 | x-oauth-scopes: repo
```

## Mapping from `tools/inspect-target.ps1`

| PowerShell block | Java |
|---|---|
| `=== REPO ===` | `GitHubService.getRepositoryInfo()` |
| `--- branches ---` | `getBranches()` / `getProtectedBranches()` |
| `--- compare master...develop ---` | `compare(base, head)` |
| `--- last N commits ---` | `getRecentCommits(ref, limit)` |
| `=== TREE ===` | `getTree(ref)` |
| `=== MAVEN MODULES ===` | `findMavenModulePoms(ref)` |
| `Get-Raw` | `getFileContent(path, ref)` |
| `=== OPEN PRs ===` | `getPullRequests(state, base, limit)` |
| `=== TOKEN SCOPES ===` | `getTokenInfo()` |

The script stays as a zero-dependency fallback; the service is the reusable implementation.

## Secrets

The GitHub PAT is **never stored in the repo**. `application.properties` binds
`factory.github.token=${GITHUB_TOKEN}`, resolved from the `GITHUB_TOKEN` environment variable at
startup. Set it before running:

```powershell
$env:GITHUB_TOKEN = "ghp_..."
java -jar target\software-factory-0.0.1-SNAPSHOT.jar
```

(or configure it as an environment variable on the IDE run configuration). If `GITHUB_TOKEN` is
unset, startup fails fast with a placeholder-resolution error instead of silently using a blank
token.

## Next

`SnykCliClient` -> `FindingNormalizer` -> `ModuleRegistry` -> `PolicyEngine` -> `MavenAdapter`
-> `BuildRunner` -> `PullRequestService`. See plan.md §12.

