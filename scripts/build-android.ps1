param(
    [string[]]$Tasks = @(':core:test', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug'),
    [string]$ToolchainRoot,
    # Directory produced by `ossCli build`; its files must match android/app/oss-assets.lock.
    [string]$OssResources,
    # Optional zenz judge: directory with arm64-v8a/libastra_zenz.so and the GGUF; both must match android/app/zenz-assets.lock.
    [string]$ZenzLibDir,
    [string]$ZenzModel
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$tools = if ($ToolchainRoot) { (Resolve-Path -LiteralPath $ToolchainRoot).Path } else { Join-Path $root '.astra/local/toolchains' }
$jdk = Get-ChildItem (Join-Path $tools 'jdk') -Directory | Select-Object -First 1
if (!$jdk) { throw 'Project-local JDK missing; see docs/android-trial.md.' }
$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = Join-Path $tools 'sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $tools 'android-user'
$env:GRADLE_USER_HOME = Join-Path $tools 'gradle-user'
$env:ASTRA_TOOLCHAINS = $tools
$env:ASTRA_OSS_RESOURCES = if ($OssResources) { (Resolve-Path -LiteralPath $OssResources).Path } else { '' }
$env:ASTRA_ZENZ_LIB_DIR = if ($ZenzLibDir) { (Resolve-Path -LiteralPath $ZenzLibDir).Path } else { '' }
$env:ASTRA_ZENZ_MODEL = if ($ZenzModel) { (Resolve-Path -LiteralPath $ZenzModel).Path } else { '' }
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$key = Join-Path $env:ANDROID_USER_HOME 'debug.keystore'
if (!(Test-Path -LiteralPath $key)) {
    New-Item -ItemType Directory -Force $env:ANDROID_USER_HOME | Out-Null
    & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore $key -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw 'Debug signing key generation failed' }
}
$gradle = Join-Path $tools 'gradle/gradle-8.13/bin/gradle.bat'
& (Join-Path $PSScriptRoot 'export-android-lexicon.ps1')
& (Join-Path $PSScriptRoot 'export-feedback-cases.ps1')
& $gradle -p (Join-Path $root 'android') --no-daemon --console=plain @Tasks
if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
