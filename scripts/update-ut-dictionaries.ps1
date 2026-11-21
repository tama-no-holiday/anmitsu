# Maintainer workflow for the Mozc-UT dictionary expansion (docs/dictionary-update.md). Never touches the current
# assets, android/app/oss-assets.lock or the signing key: everything goes to a new -StageDir. Network is used only
# with -CheckLatest or -Fetch, through the explicitly given -Python (run isolated with -I).
param(
    [string]$ToolchainRoot,
    [string]$Python,
    [switch]$CheckLatest,
    [switch]$Fetch,
    # Mozc dictionary_oss directory at the pinned revision (fetch-mozc-data.ps1); its id.def must match data-lock.json.
    [string]$MozcDir,
    [string]$CacheDir,
    [string[]]$Sources = @('sudachidict'),
    # Fetched source directories; default <CacheDir>/<name>/<lock revision> for each of -Sources.
    [string[]]$SourceDirs,
    # Extra sources already in the shipped build (e.g. the SudachiEnglish file); passed to ut-prepare and build.
    [string[]]$PriorExtra = @(),
    # Compiled resources of the currently shipped build, for the old/new comparison gate.
    [string]$CurrentResources,
    [string]$StageDir,
    [switch]$CheckOnly,
    [int]$MaxRegressions = 0,
    # JVM heap for ossCli; three UT sources plus Mozc need more than the 2g default.
    [string]$Heap = '4g'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$lockPath = Join-Path $root 'third_party/mozc-ut/sources.lock.json'
$fetchScript = Join-Path $PSScriptRoot 'fetch-ut-dictionaries.py'
if (!$CacheDir) { $CacheDir = Join-Path $root '.astra/local/oss-ut' }

function Invoke-Python([string[]]$Arguments) {
    if (!$Python) { throw '-Python <path to python.exe> is required for -CheckLatest/-Fetch' }
    & $Python -I $fetchScript @Arguments
    if ($LASTEXITCODE -ne 0) { throw "fetch-ut-dictionaries.py failed: $LASTEXITCODE" }
}

function Invoke-OssCli([string]$Arguments) {
    $build = Join-Path $PSScriptRoot 'build-android.ps1'
    $tasks = @(':oss-converter:ossCli', "--args=$Arguments", "-PossHeap=$Heap")
    if ($ToolchainRoot) { & $build -ToolchainRoot $ToolchainRoot -Tasks $tasks }
    else { & $build -Tasks $tasks }
}

if ($CheckLatest) {
    Invoke-Python @('check-latest', '--lock', $lockPath)
    return
}
$lock = Get-Content $lockPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($Fetch) {
    foreach ($name in $Sources) { Invoke-Python @('fetch', '--lock', $lockPath, '--source', $name, '--cache', $CacheDir) }
}

if (!$MozcDir) { throw '-MozcDir <dictionary_oss directory> is required' }
$mozc = (Resolve-Path -LiteralPath $MozcDir).Path
$idDefSha = ((Get-Content (Join-Path $root 'third_party/mozc/data-lock.json') -Raw | ConvertFrom-Json).files |
    Where-Object { $_.file -eq 'id.def' }).sha256
if ($idDefSha -notmatch '^[0-9a-f]{64}$') { throw 'id.def hash missing from third_party/mozc/data-lock.json' }

if (!$SourceDirs) {
    $SourceDirs = foreach ($name in $Sources) {
        $entry = $lock.sources | Where-Object { $_.name -eq $name }
        if (!$entry -or !$entry.revision) { throw "No pinned revision for $name; pass -SourceDirs explicitly" }
        Join-Path (Join-Path $CacheDir $name) $entry.revision
    }
}
$sourceArgs = ($SourceDirs | ForEach-Object { "--source $((Resolve-Path -LiteralPath $_).Path)" }) -join ' '
$priorArgs = ($PriorExtra | ForEach-Object { "--prior-extra $((Resolve-Path -LiteralPath $_).Path)" }) -join ' '
$extraArgs = ($PriorExtra | ForEach-Object { "--extra $((Resolve-Path -LiteralPath $_).Path)" }) -join ' '

if ($CheckOnly) {
    Invoke-OssCli "ut-prepare --mozc-dir $mozc --id-def-sha256 $idDefSha $sourceArgs $priorArgs --check-only"
    return
}

if (!$StageDir) { $StageDir = Join-Path $root ".astra/local/oss/staged/ut-$(Get-Date -Format 'yyyyMMdd-HHmmss')" }
if (Test-Path -LiteralPath $StageDir) { throw "Stage directory already exists: $StageDir (staging never overwrites)" }
New-Item -ItemType Directory $StageDir | Out-Null
$stage = (Resolve-Path -LiteralPath $StageDir).Path
$ut = Join-Path $stage 'ut'
$compiled = Join-Path $stage 'compiled'

# 1. Prepare: placeholder ids resolved by POS name, duplicates dropped, provenance + notices.
Invoke-OssCli "ut-prepare --mozc-dir $mozc --id-def-sha256 $idDefSha $sourceArgs $priorArgs --out $ut"
# 2. Build a separate candidate with the same extras as the shipped build plus the UT file.
Invoke-OssCli "build --mozc-dir $mozc $extraArgs --extra $(Join-Path $ut 'ut-extra.txt') --out $compiled"
# 2b. Memory-mappable lexicon.bin (what the APK ships), audited against the upstream files.
Invoke-OssCli "pack --resources $compiled"
# 3. Regression gate against the current build (fails the script on regressions; nothing is adopted either way).
if ($CurrentResources) {
    $current = (Resolve-Path -LiteralPath $CurrentResources).Path
    $fixtures = "--fixtures $(Join-Path $root 'android/core/src/test/resources/feedback.tsv') --fixtures $(Join-Path $root 'data/dictionary-probes.tsv')"
    Invoke-OssCli "compare --old $current --new $compiled $fixtures --limit 5 --max-regressions $MaxRegressions"
}
# 4. Candidate lock lines for review only; android/app/oss-assets.lock is not modified.
$names = @('lexicon.bin', 'connectionId.dat', 'oss-manifest.properties')
$lines = foreach ($n in $names) { "$((Get-FileHash -LiteralPath (Join-Path $compiled $n) -Algorithm SHA256).Hash.ToLowerInvariant())  $n" }
[IO.File]::WriteAllText((Join-Path $stage 'oss-assets.lock.candidate'), (($lines -join "`n") + "`n"))
Write-Output "Staged candidate in $stage (not adopted). Review ut/ut-summary.txt, ut/ut-provenance.txt and the compare output."
