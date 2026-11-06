$ErrorActionPreference = "Stop"
$Workspace = $PSScriptRoot
$SdkDir = Join-Path $Workspace ".android-sdk"
$CmdToolsZip = Join-Path $Workspace ".android-sdk\cmdline-tools.zip"
$CmdToolsDir = Join-Path $SdkDir "cmdline-tools\latest"
$GradleDir = Join-Path $Workspace ".gradle-dist\gradle-8.9"
$GradleZip = Join-Path $Workspace ".gradle-dist\gradle-8.9-bin.zip"

Write-Host "==> 1. Preparing local Android SDK directory..."
if (!(Test-Path $SdkDir)) {
    New-Item -ItemType Directory -Force -Path $SdkDir | Out-Null
}

if (!(Test-Path (Join-Path $CmdToolsDir "bin\sdkmanager.bat"))) {
    Write-Host "==> Downloading Android Command-Line Tools..."
    $Url = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    Invoke-WebRequest -Uri $Url -OutFile $CmdToolsZip -UseBasicParsing
    $ExtractTmp = Join-Path $SdkDir "cmdline-tools-tmp"
    if (Test-Path $ExtractTmp) { Remove-Item -Recurse -Force $ExtractTmp }
    Expand-Archive -Path $CmdToolsZip -DestinationPath $ExtractTmp -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $SdkDir "cmdline-tools") | Out-Null
    if (Test-Path $CmdToolsDir) { Remove-Item -Recurse -Force $CmdToolsDir }
    Move-Item -Path (Join-Path $ExtractTmp "cmdline-tools") -Destination $CmdToolsDir
    Remove-Item -Recurse -Force $ExtractTmp
    Remove-Item -Force $CmdToolsZip
}

$SdkManager = Join-Path $CmdToolsDir "bin\sdkmanager.bat"
$env:ANDROID_HOME = $SdkDir
$env:ANDROID_SDK_ROOT = $SdkDir

Write-Host "==> 2. Accepting Android SDK licenses & installing Platform 35 + Build-Tools 35.0.0..."
$YesInputs = ("y`n" * 30)
$YesInputs | & $SdkManager --sdk_root="$SdkDir" --licenses | Out-Null
$YesInputs | & $SdkManager --sdk_root="$SdkDir" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

$EscapedSdk = $SdkDir.Replace("\", "\\")
Set-Content -Path (Join-Path $Workspace "local.properties") -Value "sdk.dir=$EscapedSdk"

Write-Host "==> 3. Preparing Gradle 8.9..."
if (!(Test-Path (Join-Path $GradleDir "bin\gradle.bat"))) {
    New-Item -ItemType Directory -Force -Path (Join-Path $Workspace ".gradle-dist") | Out-Null
    $GUrl = "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
    Invoke-WebRequest -Uri $GUrl -OutFile $GradleZip -UseBasicParsing
    Expand-Archive -Path $GradleZip -DestinationPath (Join-Path $Workspace ".gradle-dist") -Force
    Remove-Item -Force $GradleZip
}

$GradleBat = Join-Path $GradleDir "bin\gradle.bat"

Write-Host "==> 4. Building APK with Gradle assembleDebug..."
Push-Location $Workspace
try {
    & $GradleBat assembleDebug --no-daemon
} finally {
    Pop-Location
}

$BuiltApk = Join-Path $Workspace "app\build\outputs\apk\debug\app-debug.apk"
$DestApk = Join-Path $Workspace "CDDeck-debug.apk"
if (Test-Path $BuiltApk) {
    Copy-Item -Path $BuiltApk -Destination $DestApk -Force
    Write-Host "==> SUCCESS! APK generated at: $DestApk"
} else {
    Write-Error "APK was not found at expected path: $BuiltApk"
}
