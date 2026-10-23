<#
.SYNOPSIS
  Builds the macOS client packages (Apple Silicon arm64 + Intel x64) on Windows, macOS or Linux.

.DESCRIPTION
  1. mvn package for srd-client (skip with -SkipBuild to reuse srd-client\target\SecureRemoteDesktopClient.jar)
  2. build\package_macos.py: SecureRemoteDesktopClient.app with a bundled Temurin JRE 21, as
       dist\SecureRemoteDesktopClient-<ver>-macos-arm64.tar.gz   (M1 / M2 / M3 / M4)
       dist\SecureRemoteDesktopClient-<ver>-macos-x64.tar.gz     (Intel Macs)
  Needs Python 3 and internet access the first time (JREs are cached in build\.cache\jre).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File build\build-macos.ps1
  powershell -ExecutionPolicy Bypass -File build\build-macos.ps1 -SkipBuild -Arch arm64
#>
param(
    [switch]$SkipBuild,
    [switch]$SkipTests,
    [ValidateSet('both', 'arm64', 'x64')][string]$Arch = 'both',
    [switch]$Online
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
[xml]$pom = Get-Content (Join-Path $root 'pom.xml')
$version = $pom.project.version

if (-not $SkipBuild) {
    $mvnArgs = @('-f', (Join-Path $root 'pom.xml'), 'clean', 'package', '-pl', 'srd-client', '-am')
    if (-not $Online) { $mvnArgs = @('-o') + $mvnArgs }
    if ($SkipTests) { $mvnArgs += '-DskipTests' }
    Write-Host "==> mvn $($mvnArgs -join ' ')" -ForegroundColor Cyan
    & mvn @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed ($LASTEXITCODE)" }
}

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command python3 -ErrorAction Stop }
# Prefer the build venv (has pycdlib for .dmg); fall back to system python.
$venvPy = Join-Path $root 'build\.cache\venv\Scripts\python.exe'
$pyExe = if (Test-Path $venvPy) { $venvPy } else { $python.Source }
& $pyExe (Join-Path $root 'build\package_macos.py') --version $version --arch $Arch
if ($LASTEXITCODE -ne 0) { throw "macOS packaging failed ($LASTEXITCODE)" }
Get-ChildItem (Join-Path $root "dist\*macos*") | Format-Table Name,
    @{n='MB';e={[math]::Round($_.Length/1MB,1)}}, LastWriteTime -AutoSize
