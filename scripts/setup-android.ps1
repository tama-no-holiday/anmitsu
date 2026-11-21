# Downloads build tools only; never uploads source or input. Uses project-local caches.
param(
    # Same meaning as build-android.ps1 -ToolchainRoot; created if missing.
    [string]$ToolchainRoot,
    # Only for an owner who has already reviewed and accepted the official Android SDK terms (e.g. cloud CI).
    # Without it the SDK manager keeps prompting interactively, as before.
    [switch]$AcceptAndroidSdkLicenses
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$toolRoot = if ($ToolchainRoot) { $ToolchainRoot } else { Join-Path $root '.astra/local/toolchains' }
$lock = Get-Content (Join-Path $root 'android/toolchains.lock.json') -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Force $toolRoot | Out-Null
$toolRoot = (Resolve-Path -LiteralPath $toolRoot).Path
foreach ($name in @('jdk', 'gradle', 'android')) {
    $item = $lock.$name
    $archive = Join-Path $toolRoot $item.archive
    if (!(Test-Path -LiteralPath $archive)) {
        Invoke-WebRequest -Uri $item.url -OutFile $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $item.sha256) {
        throw "Checksum mismatch for $name. Inspect the local archive before retrying."
    }
    $destination = Join-Path $toolRoot $name
    if (!(Test-Path -LiteralPath $destination)) {
        Expand-Archive -LiteralPath $archive -DestinationPath $destination
    }
}
$env:JAVA_HOME = (Get-ChildItem (Join-Path $toolRoot 'jdk') -Directory | Select-Object -First 1).FullName
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'
$sdk = Join-Path $toolRoot 'sdk'
$manager = Join-Path $toolRoot 'android/cmdline-tools/bin/sdkmanager.bat'
if ($AcceptAndroidSdkLicenses) {
    # A fixed number of answers (one review prompt plus one per license), never an endless `yes`:
    # if more prompts appear than answers, stdin ends and the SDK manager stops instead of hanging.
    $answers = @('y') * 32
    $answers | & $manager "--sdk_root=$sdk" --licenses | Tee-Object -Variable licenseLog
    $licenseExit = $LASTEXITCODE
    if ($licenseExit -ne 0) { throw "SDK license acceptance failed: $licenseExit" }
    # The SDK manager prints this both when everything was already accepted and after the last answer.
    if (($licenseLog -join "`n") -notmatch 'All SDK package licenses accepted') { throw 'SDK licenses were not all accepted. Review SDK manager output.' }
}
# Only required packages; SDK manager prompts for any outstanding licenses.
& $manager "--sdk_root=$sdk" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
if ($LASTEXITCODE -ne 0) { throw 'SDK setup failed. Review SDK manager output.' }
