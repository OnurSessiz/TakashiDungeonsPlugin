# Server-free tests for the generation package - see scripts\geo-probe\README.md
#
# Usage:
#   ... -File scripts\geo-probe\run.ps1          # GeoProbe + GenProbe + DungeonProbe
#   ... -File scripts\geo-probe\run.ps1 -Rot     # measures WorldEdit's rotation sign

param([switch]$Rot)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$probeDir = "$PSScriptRoot"
$outDir = "$root\target\geo-probe"
$classes = "$root\target\classes"

if (-not (Test-Path $classes)) {
    throw "target\classes yok. Once: powershell -ExecutionPolicy Bypass -File scripts\build.ps1"
}

# JDK 21 is pinned: a newer JDK may be on PATH (see scripts\build.ps1).
$jdk21 = Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-21*" -Directory -ErrorAction SilentlyContinue |
         Sort-Object Name | Select-Object -Last 1
if (-not $jdk21) { throw "Temurin JDK 21 bulunamadi." }
$javac = "$($jdk21.FullName)\bin\javac.exe"
$java = "$($jdk21.FullName)\bin\java.exe"

New-Item -ItemType Directory -Force $outDir | Out-Null

if ($Rot) {
    # RotProbe calls WorldEdit's own transform -> it needs worldedit-core.
    $m2 = "$env:USERPROFILE\.m2\repository"
    $we = Get-ChildItem "$m2\com\sk89q\worldedit\worldedit-core" -Recurse -Filter "worldedit-core-*.jar" |
          Select-Object -First 1
    $weLibs = Get-ChildItem "$m2\com\sk89q\worldedit\worldedit-libs\core" -Recurse -Filter "core-*.jar" |
              Select-Object -First 1
    if (-not $we) { throw "worldedit-core jar'i .m2'de yok. Once build calistirin." }
    $cp = "$($we.FullName);$($weLibs.FullName)"

    & $javac -cp $cp -d $outDir "$probeDir\RotProbe.java"
    if ($LASTEXITCODE -ne 0) { throw "RotProbe derlenemedi" }
    & $java -cp "$outDir;$cp" RotProbe
    exit $LASTEXITCODE
}

# RotProbe is deliberately EXCLUDED: it is the only probe that needs WorldEdit, and it runs
# separately via -Rot. The others depend only on the generation package -- no third-party jar.
$sources = @(
    "$probeDir\Rooms.java",
    "$probeDir\GeoProbe.java",
    "$probeDir\GenProbe.java",
    "$probeDir\DungeonProbe.java"
)
& $javac -cp $classes -d $outDir $sources
if ($LASTEXITCODE -ne 0) { throw "Probe'lar derlenemedi" }

$failed = 0
$probes = @(
    @("FAZ 1B - geometri",            "GeoProbe"),
    @("FAZ 1C - secim + cakisma",     "GenProbe"),
    @("FAZ 1D - graf uretimi",        "DungeonProbe")
)

foreach ($p in $probes) {
    Write-Host ""
    Write-Host "################ $($p[0]) ################" -ForegroundColor Cyan
    & $java -cp "$outDir;$classes" $p[1]
    if ($LASTEXITCODE -ne 0) { $failed = 1 }
}

Write-Host ""
if ($failed -ne 0) {
    Write-Host "TESTLER BASARISIZ" -ForegroundColor Red
} else {
    Write-Host "TUM TESTLER GECTI" -ForegroundColor Green
}
exit $failed
