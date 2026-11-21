# -CacheDir verifies another checkout's cache (e.g. the parent's from a dedicated worktree); it never downloads.
param([switch]$VerifyOnly, [string]$CacheDir)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$lock = Get-Content (Join-Path $root 'third_party/mozc/data-lock.json') -Raw | ConvertFrom-Json
if ($lock.revision -notmatch '^[0-9a-f]{40}$') { throw 'Invalid source revision' }
if ($CacheDir) {
    if (!$VerifyOnly) { throw '-CacheDir is read-only; use it with -VerifyOnly' }
    $destination = (Resolve-Path -LiteralPath $CacheDir).Path
} else {
    $destination = Join-Path $root '.astra/local/oss/mozc'
    New-Item -ItemType Directory -Force $destination | Out-Null
}
foreach ($entry in $lock.files) {
    if ($entry.file -notmatch '^[a-zA-Z0-9_.-]+$' -or $entry.file -in @('.', '..')) { throw 'Invalid data filename' }
    if ($entry.sha256 -notmatch '^[0-9a-f]{64}$') { throw 'Invalid digest' }
    if ($entry.path -notmatch '^(LICENSE|src/data/dictionary_oss/[a-zA-Z0-9_.-]+)$') { throw 'Invalid source path' }
    $expectedUrl = "https://raw.githubusercontent.com/google/mozc/$($lock.revision)/$($entry.path)"
    if ($entry.url -cne $expectedUrl) { throw 'Unexpected data origin' }
    $target = Join-Path $destination $entry.file
    if (!(Test-Path -LiteralPath $target)) {
        if ($VerifyOnly) { throw "Missing pinned resource: $($entry.file)" }
        $download = "$target.download"
        Invoke-WebRequest -Uri $expectedUrl -OutFile $download
        if ((Get-Item -LiteralPath $download).Length -ne $entry.bytes -or
            (Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.sha256) {
            throw "Downloaded resource failed integrity check: $($entry.file)"
        }
        Move-Item -LiteralPath $download -Destination $target
    }
    if ((Get-Item -LiteralPath $target).Length -ne $entry.bytes -or
        (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.sha256) {
        throw "Cached resource differs from lock: $($entry.file)"
    }
}
Write-Output "Verified $($lock.files.Count) Mozc resources at $($lock.revision)."
