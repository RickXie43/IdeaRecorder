$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradle = Get-Command gradle.bat -ErrorAction SilentlyContinue
if ($gradle) {
    $gradlePath = $gradle.Source
} else {
    $gradlePath = "C:\Users\Rick\.gradle\wrapper\dists\gradle-9.2.1-bin\2t0n5ozlw9xmuyvbp7dnzaxug\gradle-9.2.1\bin\gradle.bat"
}
if (-not (Test-Path -LiteralPath $gradlePath)) { throw "找不到 Gradle：$gradlePath" }

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$dist = Join-Path $projectRoot "dist"
New-Item -ItemType Directory -Path $dist -Force | Out-Null

function Build-And-Copy($directory, $debugName, $releaseName) {
    Push-Location $directory
    try {
        & $gradlePath :app:assembleDebug :app:assembleRelease --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "构建失败：$directory" }
        Copy-Item (Join-Path $directory "app\build\outputs\apk\debug\app-debug.apk") (Join-Path $dist $debugName) -Force
        Copy-Item (Join-Path $directory "app\build\outputs\apk\release\app-release.apk") (Join-Path $dist $releaseName) -Force
    } finally {
        Pop-Location
    }
}

Build-And-Copy $projectRoot "Idea-Recorder-1.0.0-debug.apk" "Idea-Recorder-1.0.0-release.apk"
Build-And-Copy (Join-Path $projectRoot "harmony-compat") "Idea-Recorder-Harmony-1.0.0-debug.apk" "Idea-Recorder-Harmony-1.0.0-release.apk"

Write-Host "构建完成，产物位于：$dist"
Get-ChildItem -LiteralPath $dist -Filter "*.apk" | Select-Object Name, Length, LastWriteTime
