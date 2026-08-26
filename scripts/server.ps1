# TakashiDungeons - local Paper test server (1.21.8)
# Usage: powershell -ExecutionPolicy Bypass -File scripts\server.ps1
#
# Paper 1.21.8 requires Java 21. With a newer JDK on PATH the server refuses to start, so the
# path to java.exe is given explicitly here.

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
    # Argumanlar diziden veriliyor ve TIRNAKLI: Windows PowerShell 5.1, tirnaksiz
    # -Dfile.encoding=UTF-8 tokenini noktadan bolup java'ya iki ayri arguman olarak
    # geciriyor ("Could not find or load main class .encoding=UTF-8").
    $javaArgs = @(
        '-Xms2G', '-Xmx2G', '-XX:+UseG1GC', '-Dfile.encoding=UTF-8',
        '-jar', 'paper.jar', 'nogui'
    )
    & $java @javaArgs
} finally {
    Pop-Location
}
