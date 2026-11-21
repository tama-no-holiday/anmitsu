# Downloads (or with -VerifyOnly only checks) the Mozc rewriter data pinned in third_party/mozc/supplement-lock.json
# into an ignored directory, for `ossCli supplement-prepare`. Only raw.githubusercontent.com at the pinned revision is
# contacted; nothing is uploaded. A file whose size or SHA-256 differs from the lock is deleted and the script fails.
param(
    [string]$OutDir,
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$lock = Get-Content (Join-Path $root 'third_party/mozc/supplement-lock.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($lock.revision -notmatch '^[0-9a-f]{40}$') { throw 'supplement-lock.json has no pinned revision' }
if (!$OutDir) { $OutDir = Join-Path $root '.astra/local/oss/mozc-supplement' }
New-Item -ItemType Directory -Force $OutDir | Out-Null
foreach ($f in $lock.files) {
    $target = Join-Path $OutDir $f.file
    if (!$VerifyOnly) {
        Invoke-WebRequest -UseBasicParsing "https://raw.githubusercontent.com/google/mozc/$($lock.revision)/$($f.path)" -OutFile $target
    }
    if (!(Test-Path -LiteralPath $target)) { throw "Missing $($f.file)" }
    $size = (Get-Item -LiteralPath $target).Length
    $sha = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($size -ne $f.bytes -or $sha -ne $f.sha256) {
        if (!$VerifyOnly) { Remove-Item -LiteralPath $target }
        throw "$($f.file) does not match supplement-lock.json"
    }
    Write-Output "ok $($f.file) $sha"
}
