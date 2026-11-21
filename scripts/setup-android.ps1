# Downloads build tools only; never uploads source or input. Uses project-local caches.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$toolRoot = Join-Path $root '.astra/local/toolchains'
$lock = Get-Content (Join-Path $root 'android/toolchains.lock.json') -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Force $toolRoot | Out-Null
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
# Only required packages; SDK manager prompts for any outstanding licenses.
& $manager "--sdk_root=$sdk" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
if ($LASTEXITCODE -ne 0) { throw 'SDK setup failed. Review SDK manager output.' }
