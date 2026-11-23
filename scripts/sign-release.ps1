#Requires -Version 7
# Signs a verified unsigned 1.0.0 release APK into a new file. Run by hand only; see docs/releases/local-signing.md.
# Passwords are read by apksigner from the named environment variables (env:NAME); this script never sees them as arguments.
param(
    [Parameter(Mandatory)][string]$InputApk,
    [Parameter(Mandatory)][string]$OutputApk,
    [Parameter(Mandatory)][string]$ToolchainRoot,
    [Parameter(Mandatory)][string]$KeystorePath,
    [string]$KeyAlias,
    [Parameter(Mandatory)][string]$ExpectedInputSha256,
    [Parameter(Mandatory)][string]$ExpectedCertSha256,
    [Parameter(Mandatory)][string]$KeystorePassEnv,
    [string]$KeyPassEnv
)
$ErrorActionPreference = 'Stop'
# The unsigned check below expects apksigner verify to fail; exit codes are checked by hand everywhere.
$PSNativeCommandUseErrorActionPreference = $false

function ConvertTo-Sha256Hex([string]$Value, [string]$Name) {
    $hex = ($Value -replace '[\s:]', '').ToLowerInvariant()
    if ($hex -notmatch '^[0-9a-f]{64}$') { throw "$Name is not a SHA-256 hex digest" }
    $hex
}
function Assert-EnvName([string]$Name) {
    if ($Name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { throw "Invalid environment variable name: $Name" }
    if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($Name))) { throw "Environment variable $Name is not set" }
}
function Test-SamePath([string]$A, [string]$B) { [string]::Equals($A, $B, [StringComparison]::OrdinalIgnoreCase) }

$expectedInput = ConvertTo-Sha256Hex $ExpectedInputSha256 'ExpectedInputSha256'
$expectedCert = ConvertTo-Sha256Hex $ExpectedCertSha256 'ExpectedCertSha256'
Assert-EnvName $KeystorePassEnv
if ($KeyPassEnv) { Assert-EnvName $KeyPassEnv }

if (!(Test-Path -LiteralPath $InputApk -PathType Leaf)) { throw 'Input APK not found' }
if (!(Test-Path -LiteralPath $KeystorePath -PathType Leaf)) { throw 'Keystore not found' }
$in = (Resolve-Path -LiteralPath $InputApk).Path
$ks = (Resolve-Path -LiteralPath $KeystorePath).Path
$out = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputApk)
$idsig = "$out.idsig"
if ([IO.Path]::GetExtension($out) -ne '.apk') { throw 'Output must be an .apk file' }
if (!(Test-Path -LiteralPath ([IO.Path]::GetDirectoryName($out)) -PathType Container)) { throw 'Output directory does not exist' }
if ((Test-Path -LiteralPath $out) -or (Test-Path -LiteralPath $idsig)) { throw "Refusing to overwrite: $out (or its .idsig) already exists" }
if ((Test-SamePath $in $out) -or (Test-SamePath $ks $out)) { throw 'Output must differ from the input APK and the keystore' }
$root = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent)).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if ($ks.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw 'Keystore must live outside the repository' }

if (!(Test-Path -LiteralPath $ToolchainRoot -PathType Container)) { throw 'ToolchainRoot not found' }
$tools = (Resolve-Path -LiteralPath $ToolchainRoot).Path
$jdk = Get-ChildItem -LiteralPath (Join-Path $tools 'jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if (!$jdk) { throw 'No JDK under ToolchainRoot/jdk' }
$buildTools = Join-Path $tools 'sdk/build-tools/35.0.0'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$aapt = Join-Path $buildTools 'aapt.exe'
$zipalign = Join-Path $buildTools 'zipalign.exe'
foreach ($tool in $apksigner, $aapt, $zipalign) { if (!(Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Missing tool: $tool" } }

# Identity, permissions, debuggability and 16 KB alignment; shared by the input and the signed output.
function Assert-ReleaseApk([string]$Apk, [string]$Label) {
    $badging = & $aapt dump badging $Apk
    if ($LASTEXITCODE -ne 0) { throw "$Label manifest inspection failed" }
    $package = @($badging | Where-Object { $_ -like 'package:*' })
    if ($package.Count -ne 1) { throw "$Label has no single package line" }
    if ($package[0] -notmatch "\bname='io\.github\.tama_no_holiday\.anmitsu'") { throw "$Label package mismatch" }
    if ($package[0] -notmatch "\bversionCode='1'") { throw "$Label versionCode mismatch" }
    if ($package[0] -notmatch "\bversionName='1\.0\.0'") { throw "$Label versionName mismatch" }
    if (($badging -join "`n") -match '(?m)^application-debuggable') { throw "$Label is debuggable" }
    $permissions = & $aapt dump permissions $Apk
    if ($LASTEXITCODE -ne 0) { throw "$Label permission inspection failed" }
    if (($permissions -join "`n") -match 'uses-permission') { throw "$Label declares a permission" }
    & $zipalign -c -P 16 4 $Apk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "$Label is not zip-aligned (4, 16 KB pages)" }
}

$savedJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $jdk.FullName
try {
    if ((Get-FileHash -LiteralPath $in -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedInput) { throw 'Input SHA-256 mismatch' }
    # Unsigned is necessary, not sufficient: the manifest and alignment checks must also pass.
    & $apksigner verify $in 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { throw 'Input APK is already signed' }
    $entries = & $aapt list $in
    if ($LASTEXITCODE -ne 0) { throw 'Input content listing failed' }
    if ($entries | Where-Object { $_ -match '^META-INF/[^/]+\.(SF|RSA|DSA|EC)$' }) { throw 'Input carries JAR signature files' }
    Assert-ReleaseApk $in 'Input'

    $signArgs = @('sign', '--ks', $ks, '--ks-pass', "env:$KeystorePassEnv")
    if ($KeyAlias) { $signArgs += '--ks-key-alias', $KeyAlias }
    if ($KeyPassEnv) { $signArgs += '--key-pass', "env:$KeyPassEnv" }
    $signArgs += '--v4-signing-enabled', 'false', '--out', $out, $in
    & $apksigner @signArgs
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed (exit $LASTEXITCODE); any partial output is left at $out and is not a release" }
} finally { $env:JAVA_HOME = $savedJavaHome }

# From here on the output exists; failures leave it in place for diagnosis and never report it ready.
$env:JAVA_HOME = $jdk.FullName
try {
    if (!(Test-Path -LiteralPath $out -PathType Leaf)) { throw 'apksigner reported success but wrote no output' }
    if (Test-Path -LiteralPath $idsig) { throw "Unexpected v4 sidecar: $idsig" }
    if ((Get-FileHash -LiteralPath $in -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedInput) { throw 'Input changed during signing' }
    $verify = & $apksigner verify --verbose --print-certs $out 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK failed verification' }
    $certs = @($verify | ForEach-Object { if ("$_" -match '^Signer #\d+ certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)\s*$') { ($Matches[1] -replace ':', '').ToLowerInvariant() } })
    if ($certs.Count -ne 1) { throw "Expected exactly one signer, found $($certs.Count)" }
    if ($certs[0] -ne $expectedCert) { throw "Signer certificate mismatch: got $($certs[0])" }
    Assert-ReleaseApk $out 'Output'
    $hash = (Get-FileHash -LiteralPath $out -Algorithm SHA256).Hash.ToLowerInvariant()
} catch {
    Write-Error "NOT READY: $out was kept for diagnosis. $($_.Exception.Message)" -ErrorAction Continue
    throw
} finally { $env:JAVA_HOME = $savedJavaHome }

Write-Output "Signed APK: $out"
Write-Output "Signer certificate SHA-256: $($certs[0])"
Write-Output "SHA-256: $hash"
