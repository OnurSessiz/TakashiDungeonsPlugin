# generation paketinin sunucusuz testleri — bkz. scripts\geo-probe\README.md
#
# Kullanim:
#   ... -File scripts\geo-probe\run.ps1          # GeoProbe + GenProbe (varsayilan)
#   ... -File scripts\geo-probe\run.ps1 -Rot     # WorldEdit rotasyon isaretini olcer

param([switch]$Rot)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$probeDir = "$PSScriptRoot"
$outDir = "$root\target\geo-probe"

if (-not (Test-Path "$root\target\classes")) {
    throw "target\classes yok. Once: powershell -ExecutionPolicy Bypass -File scripts\build.ps1"
}

# JDK 21 sabit: sistem PATH'inde daha yeni bir JDK olabilir (bkz. scripts\build.ps1).
$jdk21 = Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-21*" -Directory -ErrorAction SilentlyContinue |
         Sort-Object Name | Select-Object -Last 1
if (-not $jdk21) { throw "Temurin JDK 21 bulunamadi." }
$javac = "$($jdk21.FullName)\bin\javac.exe"
$java = "$($jdk21.FullName)\bin\java.exe"

New-Item -ItemType Directory -Force $outDir | Out-Null

if ($Rot) {
    # RotProbe WorldEdit'in kendi transform'unu cagiriyor -> worldedit-core lazim.
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

# Probe'lar sadece generation paketine bagli -- hicbir ucuncu parti jar gerekmiyor.
& $javac -cp "$root\target\classes" -d $outDir "$probeDir\GeoProbe.java" "$probeDir\GenProbe.java"
if ($LASTEXITCODE -ne 0) { throw "Probe'lar derlenemedi" }

$failed = 0

Write-Host ""
Write-Host "################ FAZ 1B — geometri ################" -ForegroundColor Cyan
& $java -cp "$outDir;$root\target\classes" GeoProbe
if ($LASTEXITCODE -ne 0) { $failed = 1 }

Write-Host ""
Write-Host "################ FAZ 1C — secim + cakisma ################" -ForegroundColor Cyan
& $java -cp "$outDir;$root\target\classes" GenProbe
if ($LASTEXITCODE -ne 0) { $failed = 1 }

Write-Host ""
if ($failed -ne 0) {
    Write-Host "TESTLER BASARISIZ" -ForegroundColor Red
} else {
    Write-Host "TUM TESTLER GECTI" -ForegroundColor Green
}
exit $failed
