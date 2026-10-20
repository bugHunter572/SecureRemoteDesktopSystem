<#
.SYNOPSIS
  Builds SecureRemoteDesktopClient.exe / SecureRemoteDesktopServer.exe and (optionally) ship-ready zips.

.DESCRIPTION
  1. mvn clean package (offline): compiles, tests and shades srd-common / srd-client / srd-server.
  2. launch4jc wraps the shaded jars:
       srd-client\bin\SecureRemoteDesktopClient.exe
       srd-server\bin\SecureRemoteDesktopServer.exe
  3. -BundleJre: jlink builds a small Java runtime (~50 MB) and copies it to <module>\bin\jre.
     The exes look for .\jre first, then JAVA_HOME / PATH, so target PCs need no Java installed.
  4. -Package: writes dist\SecureRemoteDesktopClient-<ver>-win64.zip and
     dist\SecureRemoteDesktopServer-<ver>-win64.zip with the exe (+ jre, + ffmpeg for the server).
     Runtime data (logs, config, db, screenshots) is never packaged.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File build\build-exe.ps1                      # exes only (needs Java on target)
  powershell -ExecutionPolicy Bypass -File build\build-exe.ps1 -BundleJre -Package  # ship-ready zips
  powershell -ExecutionPolicy Bypass -File build\build-exe.ps1 -Only client -SkipTests
#>
param(
    [switch]$SkipTests,
    [ValidateSet('all', 'client', 'server', 'observer')][string]$Only = 'all',
    [switch]$BundleJre,
    [switch]$Package,
    [string]$Launch4jHome = $(if ($env:LAUNCH4J_HOME) { $env:LAUNCH4J_HOME } else { 'C:\Program Files (x86)\Launch4j' }),
    [switch]$Online
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$launch4jc = Join-Path $Launch4jHome 'launch4jc.exe'
if (-not (Test-Path $launch4jc)) { throw "launch4jc.exe not found at $launch4jc (set -Launch4jHome or LAUNCH4J_HOME)" }

# Version from the parent pom (<version> right after <artifactId>secure-remote-desktop</artifactId>).
[xml]$pom = Get-Content (Join-Path $root 'pom.xml')
$version = $pom.project.version

$apps = @()
if ($Only -in 'all', 'client') { $apps += @{ Module = 'srd-client'; Exe = 'SecureRemoteDesktopClient.exe'; Name = 'SecureRemoteDesktopClient' } }
if ($Only -in 'all', 'server') { $apps += @{ Module = 'srd-server'; Exe = 'SecureRemoteDesktopServer.exe'; Name = 'SecureRemoteDesktopServer'; Config = 'launch4j.xml' } }
# Watch-only alternative server: same jar, started with --observer (see srd-server\launch4j-observer.xml).
if ($Only -in 'all', 'observer') { $apps += @{ Module = 'srd-server'; Exe = 'SecureRemoteDesktopObserver.exe'; Name = 'SecureRemoteDesktopObserver'; Config = 'launch4j-observer.xml' } }

# An exe (or its bundled jre) that is running can't be overwritten.
foreach ($a in $apps) {
    $bin = Join-Path $root "$($a.Module)\bin"
    $running = Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($bin, 'OrdinalIgnoreCase') -or ($_.CommandLine -and $_.CommandLine -like "*$bin\$($a.Exe)*") }
    if ($running) { throw "$($a.Name) is running from $bin (PID $($running.ProcessId -join ', ')). Stop it first." }
}

# ---- 1. Maven
$modules = (@($apps | ForEach-Object { $_.Module }) + 'srd-common') | Select-Object -Unique
$mvnArgs = @('-f', (Join-Path $root 'pom.xml'), 'clean', 'package', '-pl', ($modules -join ','), '-am')
if (-not $Online) { $mvnArgs = @('-o') + $mvnArgs }
if ($SkipTests) { $mvnArgs += '-DskipTests' }
Write-Host "==> mvn $($mvnArgs -join ' ')" -ForegroundColor Cyan
& mvn @mvnArgs
if ($LASTEXITCODE -ne 0) { throw "Maven build failed ($LASTEXITCODE)" }

# ---- 2. launch4j
foreach ($a in $apps) {
    $cfgName = if ($a.Config) { $a.Config } else { 'launch4j.xml' }
    $cfg = Join-Path $root "$($a.Module)\$cfgName"
    Write-Host "==> launch4jc $cfg" -ForegroundColor Cyan
    & $launch4jc $cfg
    if ($LASTEXITCODE -ne 0) { throw "launch4j failed for $($a.Module) ($LASTEXITCODE)" }
}

# ---- 3. bundled runtime
if ($BundleJre) {
    $jlink = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\jlink.exe' } else { (Get-Command jlink.exe -ErrorAction Stop).Source }
    $jre = Join-Path $root 'build\jre'
    if (Test-Path $jre) { Remove-Item -Recurse -Force $jre }
    # Modules from `jdeps --print-module-deps` on both shaded jars, plus jdk.unsupported for JNA
    # and jdk.httpserver for the client's broadcast API.
    $mods = 'java.base,java.desktop,java.logging,java.sql,jdk.unsupported,jdk.httpserver'
    Write-Host "==> jlink $mods" -ForegroundColor Cyan
    & $jlink --add-modules $mods --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output $jre
    if ($LASTEXITCODE -ne 0) { throw "jlink failed ($LASTEXITCODE)" }
    foreach ($a in $apps) {
        $dst = Join-Path $root "$($a.Module)\bin\jre"
        if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
        Copy-Item -Recurse $jre $dst
    }
    $size = [math]::Round(((Get-ChildItem $jre -Recurse | Measure-Object Length -Sum).Sum / 1MB), 1)
    Write-Host "    runtime: $size MB, copied into each bin\jre" -ForegroundColor DarkGray
}

# ---- 4. zips
if ($Package) {
    $dist = Join-Path $root 'dist'
    New-Item -ItemType Directory -Force $dist | Out-Null
    foreach ($a in $apps) {
        $bin = Join-Path $root "$($a.Module)\bin"
        $stage = Join-Path $env:TEMP "srd-pkg-$($a.Name)"
        if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
        New-Item -ItemType Directory -Force (Join-Path $stage $a.Name) | Out-Null
        $out = Join-Path $stage $a.Name
        Copy-Item (Join-Path $bin $a.Exe) $out
        if (Test-Path (Join-Path $bin 'jre')) { Copy-Item -Recurse (Join-Path $bin 'jre') $out }
        if ($a.Name -eq 'SecureRemoteDesktopServer' -and (Test-Path (Join-Path $bin 'ffmpeg.exe'))) { Copy-Item (Join-Path $bin 'ffmpeg.exe') $out }
        Copy-Item (Join-Path $root 'CHANGELOG.md') $out
        $zip = Join-Path $dist "$($a.Name)-$version-win64.zip"
        if (Test-Path $zip) { Remove-Item -Force $zip }
        Compress-Archive -Path $out -DestinationPath $zip -CompressionLevel Optimal
        Remove-Item -Recurse -Force $stage
        Write-Host "==> $zip ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)" -ForegroundColor Green
    }
}

Write-Host "Done (version $version)." -ForegroundColor Green
foreach ($a in $apps) { Get-ChildItem (Join-Path $root "$($a.Module)\bin\*.exe") | Format-Table Name, Length, LastWriteTime -AutoSize }
