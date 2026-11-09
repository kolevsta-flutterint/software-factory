param(
  [string]$Token = $env:GH_TOKEN,
  [string]$Owner = 'FlutterInt',
  [string]$Repo  = 'fips-martech-conversion-router',
  [string]$Ref   = 'develop',
  [string]$OutFile
)

$ErrorActionPreference = 'Stop'
$api = "https://api.github.com/repos/$Owner/$Repo"
$hJson = @{ Authorization = "Bearer $Token"; 'User-Agent' = 'software-factory'; Accept = 'application/vnd.github+json' }
$hRaw  = @{ Authorization = "Bearer $Token"; 'User-Agent' = 'software-factory'; Accept = 'application/vnd.github.raw' }

$out = New-Object System.Collections.Generic.List[string]
function Add-Out { param($o) $out.Add(($o | Out-String -Width 250).TrimEnd()) }

function Get-Raw([string]$path) {
  $url = "$api/contents/$($path -replace '#','%23')?ref=$Ref"
  $r = Invoke-WebRequest -UseBasicParsing -Uri $url -Headers $hRaw
  [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
}

function Sel($doc, $ns, $xp) { $n = $doc.SelectSingleNode($xp, $ns); if ($n) { $n.InnerText } else { '' } }

# ---- repo + branches ----
$repoInfo = Invoke-RestMethod $api -Headers $hJson
$branches = Invoke-RestMethod "$api/branches?per_page=100" -Headers $hJson
Add-Out "=== REPO: $Owner/$Repo (scanning ref '$Ref') ==="
Add-Out ("default_branch={0}  private={1}  language={2}  size_kb={3}  pushed={4}" -f $repoInfo.default_branch, $repoInfo.private, $repoInfo.language, $repoInfo.size, $repoInfo.pushed_at)
Add-Out "--- branches ($($branches.Count)) ---"
Add-Out ($branches | Select-Object name, @{n='protected';e={$_.protected}}, @{n='sha';e={$_.commit.sha.Substring(0,8)}} | Format-Table -AutoSize)

# ---- compare develop vs master ----
try {
  $cmp = Invoke-RestMethod "$api/compare/master...$Ref" -Headers $hJson
  Add-Out "--- compare master...$Ref ---"
  Add-Out ("status={0}  ahead_by={1}  behind_by={2}  files_changed={3}" -f $cmp.status, $cmp.ahead_by, $cmp.behind_by, $cmp.files.Count)
} catch { Add-Out "compare failed: $($_.Exception.Message)" }

# ---- last commits on ref ----
$commits = Invoke-RestMethod "$api/commits?sha=$Ref&per_page=10" -Headers $hJson
Add-Out "--- last 10 commits on $Ref ---"
Add-Out ($commits | Select-Object @{n='date';e={$_.commit.author.date}}, @{n='author';e={$_.commit.author.name}}, @{n='msg';e={($_.commit.message -split "`n")[0]}} | Format-Table -AutoSize)

# ---- tree ----
$tree = Invoke-RestMethod "$api/git/trees/$Ref`?recursive=1" -Headers $hJson
$paths = $tree.tree | Select-Object -ExpandProperty path
Add-Out "=== TREE @ $Ref : $($paths.Count) entries (truncated=$($tree.truncated)) ==="

# ---- maven modules ----
$poms = $paths | Where-Object { $_ -like '*pom.xml' }
Add-Out "=== MAVEN MODULES ($($poms.Count)) ==="
$rows = @()
foreach ($p in $poms) {
  $txt = (Get-Raw $p) -replace '^\uFEFF',''
  $x = New-Object System.Xml.XmlDocument
  $x.LoadXml($txt)
  $ns = New-Object System.Xml.XmlNamespaceManager($x.NameTable)
  $ns.AddNamespace('m','http://maven.apache.org/POM/4.0.0')
  $java = @((Sel $x $ns '/m:project/m:properties/m:maven.compiler.source'),
            (Sel $x $ns '/m:project/m:properties/m:maven.compiler.release'),
            (Sel $x $ns '/m:project/m:properties/m:java.version')) | Where-Object { $_ -ne '' } | Select-Object -First 1
  $modDir = (Split-Path $p -Parent) -replace '\\','/'
  $rows += [pscustomobject]@{
    Module     = Split-Path $modDir -Leaf
    ArtifactId = Sel $x $ns '/m:project/m:artifactId'
    Version    = Sel $x $ns '/m:project/m:version'
    Parent     = ((Sel $x $ns '/m:project/m:parent/m:artifactId') + ':' + (Sel $x $ns '/m:project/m:parent/m:version')).Trim(':')
    Java       = $java
    Deps       = @($x.SelectNodes('/m:project/m:dependencies/m:dependency',$ns)).Count
    DepMgmt    = @($x.SelectNodes('/m:project/m:dependencyManagement/m:dependencies/m:dependency',$ns)).Count
    MainJava   = @($paths | Where-Object { $_ -like "$modDir/src/main/java/*.java" }).Count
    TestJava   = @($paths | Where-Object { $_ -like "$modDir/src/test/java/*.java" }).Count
    Wrapper    = @($paths | Where-Object { $_ -eq "$modDir/mvnw" }).Count -gt 0
    Path       = $p
  }
}
Add-Out ($rows | Format-Table Module,ArtifactId,Version,Parent,Java,Deps,DepMgmt,MainJava,TestJava,Wrapper -AutoSize)
Add-Out ("TOTALS: mainJava={0} testJava={1} wrappers={2}/{3}" -f (($rows|Measure-Object MainJava -Sum).Sum), (($rows|Measure-Object TestJava -Sum).Sum), (@($rows|Where-Object Wrapper).Count), $rows.Count)

# ---- other ecosystems / config ----
Add-Out "=== OTHER MANIFESTS / CONFIG ==="
Add-Out ($paths | Where-Object { $_ -match '(^|/)(package\.json|package-lock\.json|yarn\.lock|requirements\.txt|build\.gradle(\.kts)?|\.snyk|renovate\.json|dependabot\.yml|settings\.xml|CODEOWNERS|Jenkinsfile.*)$' -or $_ -like '.github/*' })

# ---- branch protection ----
Add-Out "=== BRANCH PROTECTION ($Ref) ==="
try { Add-Out (Invoke-RestMethod "$api/branches/$Ref/protection" -Headers $hJson | ConvertTo-Json -Depth 6) }
catch { Add-Out "not available: $($_.Exception.Message)" }

# ---- dependabot ----
Add-Out "=== DEPENDABOT ALERTS ==="
try {
  $al = Invoke-RestMethod "$api/dependabot/alerts?state=open&per_page=100" -Headers $hJson
  Add-Out "open alerts: $($al.Count)"
  Add-Out ($al | Group-Object { $_.security_advisory.severity } | Format-Table Name,Count -AutoSize)
} catch { Add-Out "not available: $($_.Exception.Message)" }

# ---- PRs ----
Add-Out "=== OPEN PRs ==="
try {
  $prs = Invoke-RestMethod "$api/pulls?state=open&per_page=100" -Headers $hJson
  Add-Out "open PRs: $($prs.Count)"
  Add-Out ($prs | Select-Object number, @{n='user';e={$_.user.login}}, @{n='base';e={$_.base.ref}}, @{n='head';e={$_.head.ref}}, title | Format-Table -AutoSize)
} catch { Add-Out "not available: $($_.Exception.Message)" }

Add-Out "=== RECENT CLOSED PRs (base=$Ref) ==="
try {
  $mg = Invoke-RestMethod "$api/pulls?state=closed&base=$Ref&per_page=15&sort=updated&direction=desc" -Headers $hJson
  Add-Out ($mg | Select-Object number, @{n='user';e={$_.user.login}}, @{n='merged';e={$_.merged_at}}, title | Format-Table -AutoSize)
} catch { Add-Out "not available: $($_.Exception.Message)" }

# ---- token scopes ----
$r = Invoke-WebRequest -UseBasicParsing -Uri 'https://api.github.com/user' -Headers $hJson
Add-Out ("=== TOKEN SCOPES: " + $r.Headers['x-oauth-scopes'] + " ===")

$text = $out -join "`r`n`r`n"
if ($OutFile) { $text | Set-Content -Path $OutFile -Encoding utf8 }
$text

