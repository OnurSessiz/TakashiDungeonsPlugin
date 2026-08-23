# TakashiDungeons — yerel Paper test sunucusu (1.21.8)
# Kullanim: powershell -ExecutionPolicy Bypass -File scripts\server.ps1
#
# Paper 1.21.8 Java 21 ister. Sistem PATH'inde daha yeni bir JDK varsa sunucu
# acilmaz, bu yuzden java.exe yolu burada acikca veriliyor.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$runDir = "$root\run"

if (-not (Test-Path "$runDir\paper.jar")) {
    throw "run\paper.jar yok. Paper 1.21.8 jar'ini run\paper.jar olarak indir."
}

$jdk21 = Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-21*" -Directory -ErrorAction SilentlyContinue |
         Sort-Object Name | Select-Object -Last 1
if (-not $jdk21) { throw "Temurin JDK 21 bulunamadi. Kurulum: winget install EclipseAdoptium.Temurin.21.JDK" }
$java = "$($jdk21.FullName)\bin\java.exe"

Push-Location $runDir
try {
    & $java -Xms2G -Xmx2G -XX:+UseG1GC -Dfile.encoding=UTF-8 -jar paper.jar nogui
} finally {
    Pop-Location
}
