param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Gradle = ''
)
$ErrorActionPreference = 'Stop'
if (-not $Sdk) { throw 'Pass -Sdk with the Android SDK path.' }
$sdkPath = (Resolve-Path -LiteralPath $Sdk).Path
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path $root 'build/android'
New-Item -ItemType Directory -Force -Path $out | Out-Null

if (-not $Gradle) {
    $command = Get-Command gradle.bat -ErrorAction SilentlyContinue
    if ($command) { $Gradle = $command.Source }
    else {
        $cache = Join-Path $env:USERPROFILE '.gradle/wrapper/dists/gradle-8.13-bin'
        if (Test-Path -LiteralPath $cache) {
            $Gradle = Get-ChildItem -LiteralPath $cache -Recurse -Filter 'gradle.bat' |
                Select-Object -First 1 -ExpandProperty FullName
        }
    }
}
if (-not $Gradle) { throw 'Install Gradle 8.13 or pass -Gradle with the path to gradle.bat.' }

$keystore = Join-Path $out 'debug.keystore'
if (-not (Test-Path -LiteralPath $keystore)) {
    & keytool.exe -genkeypair -noprompt -keystore $keystore -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=SoundShelf Debug,O=Android,C=US'
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with code $LASTEXITCODE" }
}

$env:ANDROID_HOME = $sdkPath
$env:ANDROID_SDK_ROOT = $sdkPath
& $Gradle -p $root ':app:assembleDebug' "-PyinlangDebugKeystore=$keystore" '--console=plain'
if ($LASTEXITCODE -ne 0) { throw "Gradle failed with code $LASTEXITCODE" }

$config = Get-Content -Raw -LiteralPath (Join-Path $root 'app/build.gradle')
$version = [regex]::Match($config, "(?m)^\s*versionName\s+'([\d.]+)'\s*$").Groups[1].Value
if (-not $version) { throw 'Missing versionName in app/build.gradle.' }
$apk = Join-Path $out "YinLang-$version.apk"
Copy-Item -LiteralPath (Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk') -Destination $apk
Write-Output $apk
