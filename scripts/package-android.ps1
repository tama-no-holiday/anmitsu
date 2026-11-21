# -Release packages the unsigned app-release-unsigned.apk (1.0.0+); nothing here signs it.
param([string]$Version = '0.1.1-trial', [string]$ToolchainRoot, [switch]$RequireOss, [switch]$RequireZenz, [switch]$Release)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^[0-9A-Za-z.-]+$') { throw 'Invalid version label' }
$root = Split-Path $PSScriptRoot -Parent
$tools = if ($ToolchainRoot) { (Resolve-Path -LiteralPath $ToolchainRoot).Path } else { Join-Path $root '.astra/local/toolchains' }
$env:JAVA_HOME = (Get-ChildItem (Join-Path $tools 'jdk') -Directory | Select-Object -First 1).FullName
$buildTools = Join-Path $tools 'sdk/build-tools/35.0.0'
$source = if ($Release) { Join-Path $root 'android/app/build/outputs/apk/release/app-release-unsigned.apk' } else { Join-Path $root 'android/app/build/outputs/apk/debug/app-debug.apk' }
if (!(Test-Path -LiteralPath $source)) { throw 'Build the APK first.' }
$signature = & (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $source
if ($Release) {
    if ($LASTEXITCODE -eq 0) { throw 'Release APK is unexpectedly signed' }
    $signature = @('unsigned')
} elseif ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
$badging = & (Join-Path $buildTools 'aapt.exe') dump badging $source
if ($LASTEXITCODE -ne 0) { throw 'APK manifest inspection failed' }
$permissions = & (Join-Path $buildTools 'aapt.exe') dump permissions $source
if ($LASTEXITCODE -ne 0) { throw 'APK permission inspection failed' }
if (($permissions -join "`n") -match 'uses-permission') { throw 'Unexpected permission in APK' }
# 1.0.0 moved to its own package; 0.1.x was dev.astra.ime.trial.
if (($badging -join "`n") -notmatch "package: name='io\.github\.tama_no_holiday\.anmitsu'") { throw 'Unexpected package' }
if ($Release -and ($badging -join "`n") -match '(?m)^application-debuggable') { throw 'Release APK is debuggable' }
if (($badging -join "`n") -notmatch "versionName='$([regex]::Escape($Version))'") { throw 'Version label mismatch' }
# 0.1.25 renamed the app; earlier version labels keep the file name they were released under.
$isOldName = $Version -match '^(\d+\.\d+\.\d+)' -and [version]$Matches[1] -lt [version]'0.1.25'
$fileStem = if ($isOldName) { 'astra-ime' } else { 'anmitsu' }
if (!$isOldName -and ($badging -join "`n") -notmatch "(?m)^application-label:'anmitsu'") { throw 'Unexpected application label' }
$contents = & (Join-Path $buildTools 'aapt.exe') list $source
if ($LASTEXITCODE -ne 0) { throw 'APK content listing failed' }
foreach ($notice in 'Mozc-LICENSE.txt', 'Mozc-DICTIONARY-NOTICES.txt', 'kotlin-kana-kanji-converter-LICENSE.txt',
        'SudachiDict-LICENSE-2.0.txt', 'SudachiDict-LEGAL.txt', 'mecab-ipadic-neologd-COPYING.txt') {
    if ($contents -notcontains "assets/licenses/$notice") { throw "Missing notice: $notice" }
}
# English word candidates (0.1.15+): the OSS-derived index is part of every build.
if ($contents -notcontains 'assets/english/english-words.tsv') { throw 'Missing English index: assets/english/english-words.tsv' }
# Symbols, numbers, emoticons and single kanji (0.1.20+): the Mozc-derived table is part of every build.
if ($contents -notcontains 'assets/supplement/mozc-supplement.tsv') { throw 'Missing supplement: assets/supplement/mozc-supplement.tsv' }
# A Windows checkout must not reach the APK: these bytes are locked or parsed line by line (see .gitattributes).
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($source)
try {
    foreach ($entry in $zip.Entries | Where-Object { $_.FullName -in 'assets/supplement/mozc-supplement.tsv', 'assets/oss/oss-assets.sha256' }) {
        $reader = [IO.StreamReader]::new($entry.Open())
        try { if ($reader.ReadToEnd().Contains("`r")) { throw "CRLF in $($entry.FullName)" } } finally { $reader.Dispose() }
    }
} finally { $zip.Dispose() }
$ossAssets = @($contents | Where-Object { $_ -like 'assets/oss/*' })
$zenzFiles = @($contents | Where-Object { $_ -like 'assets/zenz/*' })
# Universal APK: native code in lib/ for arm64 only would refuse installation on other ABIs (1.0.0 keeps it in assets).
if (@($contents | Where-Object { $_ -like 'lib/*' }).Count -gt 0) { throw 'Unexpected lib/ entry: the APK must install on every ABI' }
if ($RequireZenz) {
    foreach ($name in 'assets/zenz/arm64-v8a/libastra_zenz.so', 'assets/zenz/zenz-assets.sha256', 'assets/zenz/zenz-v3.2-xsmall-Q5_K_M.gguf',
            'assets/licenses/llama.cpp-LICENSE.txt', 'assets/licenses/zenz-LICENSE-Apache-2.0.txt') {
        if ($contents -notcontains $name) { throw "Missing zenz file: $name" }
    }
}
if ($RequireOss) {
    foreach ($name in 'lexicon.bin', 'connectionId.dat', 'oss-manifest.properties', 'oss-assets.sha256') {
        if ($contents -notcontains "assets/oss/$name") { throw "Missing OSS asset: $name" }
    }
    # The app memory-maps lexicon.bin from the APK (AssetManager.openFd), which requires it stored uncompressed.
    $verbose = & (Join-Path $buildTools 'aapt.exe') list -v $source
    if ($LASTEXITCODE -ne 0) { throw 'APK content listing failed' }
    if (!($verbose | Where-Object { $_ -match '\sStored\s.*assets/oss/lexicon\.bin$' })) { throw 'assets/oss/lexicon.bin is not stored uncompressed' }
}
$destination = Join-Path $root 'artifacts/apk'
New-Item -ItemType Directory -Force $destination | Out-Null
$apk = Join-Path $destination $(if ($Release) { "$fileStem-$Version-unsigned.apk" } else { "$fileStem-$Version.apk" })
Copy-Item -LiteralPath $source -Destination $apk
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$apk.sha256", "$hash  $([IO.Path]::GetFileName($apk))`n")
@{
    version = $Version
    file = [IO.Path]::GetFileName($apk)
    bytes = (Get-Item -LiteralPath $apk).Length
    sha256 = $hash
    createdUtc = [DateTime]::UtcNow.ToString('o')
    manifest = @($badging | Where-Object { $_ -match '^(package:|sdkVersion:|targetSdkVersion:|launchable-activity:|application-label:)' })
    permissions = @($permissions)
    ossAssets = $ossAssets
    zenzFiles = $zenzFiles
    signature = @($signature)
    deviceTested = $false
} | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $destination 'build-info.json') -Encoding utf8
Write-Output "APK: $apk"
Write-Output "SHA-256: $hash"
