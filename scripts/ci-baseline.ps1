# Fixture-only CI baseline: no OSS dictionary or zenz input, so the result is NOT release quality.
# Runs the existing build and package checks unchanged; any failure fails the run. No official/release signing or upload;
# assembleDebug signs the debug APK with the development (debug) key only.
param([string]$ToolchainRoot)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$version = '1.0.0'
$tasks = @(':core:test', ':oss-converter:test', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug')
$toolArgs = @{}
if ($ToolchainRoot) { $toolArgs.ToolchainRoot = $ToolchainRoot }
# package-android.ps1 copies into this checkout's artifacts/apk only (ignored by git).
$apkDir = Join-Path $root 'artifacts/apk'
$reportPath = Join-Path $apkDir 'ci-baseline-report.json'
$reports = [ordered]@{
    core = 'android/core/build/reports/tests/test/index.html'
    ossConverter = 'android/oss-converter/build/reports/tests/test/index.html'
    appUnit = 'android/app/build/reports/tests/testDebugUnitTest/index.html'
    lint = 'android/app/build/reports/lint-results-debug.html'
    buildInfo = 'artifacts/apk/build-info.json'
}
Write-Output 'CI baseline: FIXTURE-ONLY (no OSS dictionary, no zenz). NOT release quality.'
Write-Output "Tasks: $($tasks -join ' ')"
$stage = 'build'
$status = 'failed'
try {
    & (Join-Path $PSScriptRoot 'build-android.ps1') -Tasks $tasks @toolArgs
    $stage = 'package'
    $packageOutput = @(& (Join-Path $PSScriptRoot 'package-android.ps1') -Version $version @toolArgs)
    $packageOutput | Write-Output
    $stage = 'fixture-check'
    $info = Get-Content -LiteralPath (Join-Path $apkDir 'build-info.json') -Raw | ConvertFrom-Json
    # A baseline that silently picked up OSS or zenz inputs would be mislabelled as fixture-only.
    if (@($info.ossAssets) -contains 'assets/oss/lexicon.bin') { throw 'OSS dictionary found in a fixture-only build' }
    if (@($info.zenzFiles).Count -gt 0) { throw 'zenz files found in a fixture-only build' }
    $stage = 'done'
    $status = 'passed'
} finally {
    New-Item -ItemType Directory -Force $apkDir | Out-Null
    [ordered]@{
        status = $status
        failedStage = if ($status -eq 'passed') { $null } else { $stage }
        quality = 'fixture-only, not release quality'
        version = $version
        tasks = $tasks
        reports = $reports
        createdUtc = [DateTime]::UtcNow.ToString('o')
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $reportPath -Encoding utf8
    Write-Output "CI baseline $status$(if ($status -ne 'passed') { " at stage: $stage" })"
    Write-Output "Report: $reportPath"
    foreach ($entry in $reports.GetEnumerator()) {
        $path = Join-Path $root $entry.Value
        $note = if (Test-Path -LiteralPath $path) { '' } else { ' (not produced)' }
        Write-Output "  $($entry.Key): $path$note"
    }
}
