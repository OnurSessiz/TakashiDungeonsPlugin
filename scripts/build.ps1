# TakashiDungeons - build + deploy
# Usage: powershell -ExecutionPolicy Bypass -File scripts\build.ps1
#
# The build runs on JDK 21: the target, Paper 1.21.8, runs on Java 21. If a newer JDK is
# installed we do not trust PATH and pick 21 explicitly.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$jdk21 = Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-21*" -Directory -ErrorAction SilentlyContinue |
         Sort-Object Name | Select-Object -Last 1
if ($jdk21) {
    $env:JAVA_HOME = $jdk21.FullName
    Write-Host "JAVA_HOME = $($jdk21.FullName)" -ForegroundColor DarkGray
} else {
    Write-Host "UYARI: Temurin JDK 21 bulunamadi, PATH'teki JDK kullanilacak." -ForegroundColor Yellow
}

Push-Location $root
try {
    & "$root\mvnw.cmd" -B clean package
    if ($LASTEXITCODE -ne 0) { throw "Maven build basarisiz (exit $LASTEXITCODE)" }

    $jar = Get-ChildItem "$root\target\TakashiDungeons-*.jar" -File |
           Where-Object { $_.Name -notlike "*-shaded.jar" -and $_.Name -notlike "original-*" } |
           Select-Object -First 1
    if (-not $jar) { throw "target\ altinda jar bulunamadi" }

    $pluginsDir = "$root\run\plugins"
    if (-not (Test-Path $pluginsDir)) { New-Item -ItemType Directory -Force $pluginsDir | Out-Null }

    # drop the old version before copying the new one (with two jars present the server
    # tries to load both)
    Get-ChildItem "$pluginsDir\TakashiDungeons-*.jar" -File -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item $jar.FullName $pluginsDir -Force

    Write-Host "OK: $($jar.Name) -> run\plugins\" -ForegroundColor Green
} finally {
    Pop-Location
}
