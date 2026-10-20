# Swaps a staged Secure Remote Desktop update into the app folder after the app has exited.
# Started by the app itself (com.srd.common.update.UpdateService); logs to update\apply.log.
#   1. waits until the exe can be written (the app and its bundled java have exited)
#   2. backs up the files it will replace, copies the new ones (config, database and logs are never touched)
#   3. starts the new exe and waits for the app to write update\ok.txt (= it stayed up and healthy)
#   4. no marker in time -> stops the new app, restores the backup, starts the old one, marks the version failed
param(
    [Parameter(Mandatory = $true)][string]$AppDir,
    [Parameter(Mandatory = $true)][string]$Stage,
    [Parameter(Mandatory = $true)][string]$Exe,
    [Parameter(Mandatory = $true)][string]$Version,
    [int]$WaitExitSeconds = 90,
    [int]$HealthSeconds = 150
)
$ErrorActionPreference = 'Stop'
$upd = Join-Path $AppDir 'update'
$logFile = Join-Path $upd 'apply.log'
$backup = Join-Path $upd 'backup'
$okFile = Join-Path $upd 'ok.txt'
$exePath = Join-Path $AppDir $Exe
New-Item -ItemType Directory -Force $upd | Out-Null

function Log([string]$m) { Add-Content -Path $logFile -Value ("{0} {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m) }

function Stop-AppProcesses {
    Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($AppDir, [StringComparison]::OrdinalIgnoreCase)
    } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

# Files in the package root that are code/binaries; settings and data files are never replaced.
function Is-Protected([string]$name) {
    return $name -match '\.(properties|db|db-journal|lock|log|vbs|txt)$' -and $name -ne 'CHANGELOG.md'
}

try {
    Log "update to $Version from $Stage"

    $deadline = (Get-Date).AddSeconds($WaitExitSeconds)
    while ($true) {
        try {
            $fs = [System.IO.File]::Open($exePath, 'Open', 'ReadWrite', 'None')
            $fs.Close()
            break
        } catch {
            if ((Get-Date) -gt $deadline) { throw "the app did not exit within $WaitExitSeconds s" }
            Start-Sleep -Milliseconds 500
        }
    }
    Start-Sleep -Seconds 1

    if (Test-Path $backup) { Remove-Item -Recurse -Force $backup }
    New-Item -ItemType Directory -Force $backup | Out-Null

    $newJre = Join-Path $Stage 'jre'
    $oldJre = Join-Path $AppDir 'jre'
    $swapJre = $false
    if (Test-Path $newJre) {
        $nr = Join-Path $newJre 'release'
        $or = Join-Path $oldJre 'release'
        $swapJre = (-not (Test-Path $oldJre)) -or (-not (Test-Path $or)) -or (-not (Test-Path $nr)) -or
            ((Get-FileHash $nr).Hash -ne (Get-FileHash $or).Hash)
    }

    $files = Get-ChildItem -Path $Stage -File | Where-Object { -not (Is-Protected $_.Name) }
    foreach ($f in $files) {
        $dst = Join-Path $AppDir $f.Name
        if (Test-Path $dst) { Copy-Item $dst (Join-Path $backup $f.Name) -Force }
    }
    if ($swapJre -and (Test-Path $oldJre)) { Move-Item $oldJre (Join-Path $backup 'jre') -Force }
    foreach ($f in $files) { Copy-Item $f.FullName (Join-Path $AppDir $f.Name) -Force }
    if ($swapJre) { Copy-Item -Recurse -Force $newJre $oldJre }
    Log "files replaced (jre swapped: $swapJre)"

    if (Test-Path $okFile) { Remove-Item $okFile -Force }
    Start-Process -FilePath $exePath -WorkingDirectory $AppDir
    Log "started new app, waiting for health marker"

    $deadline = (Get-Date).AddSeconds($HealthSeconds)
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $okFile) -and ((Get-Content $okFile -Raw).Trim() -eq $Version)) { $healthy = $true; break }
        Start-Sleep -Seconds 2
    }

    if ($healthy) {
        Log "update to $Version OK"
        Remove-Item -Recurse -Force (Join-Path $upd 'work') -ErrorAction SilentlyContinue
        exit 0
    }

    Log "new app did not report healthy - rolling back"
    Stop-AppProcesses
    Start-Sleep -Seconds 2
    foreach ($f in (Get-ChildItem -Path $backup -File)) { Copy-Item $f.FullName (Join-Path $AppDir $f.Name) -Force }
    if (Test-Path (Join-Path $backup 'jre')) {
        if (Test-Path $oldJre) { Remove-Item -Recurse -Force $oldJre }
        Move-Item (Join-Path $backup 'jre') $oldJre -Force
    }
    Set-Content -Path (Join-Path $upd "failed-$Version") -Value (Get-Date -Format 'o')
    Start-Process -FilePath $exePath -WorkingDirectory $AppDir
    Log "rolled back to the previous version"
    exit 1
} catch {
    Log "update failed: $_"
    try { Set-Content -Path (Join-Path $upd "failed-$Version") -Value (Get-Date -Format 'o') } catch { }
    # make sure the app is running again whatever happened
    try { if (Test-Path $exePath) { Start-Process -FilePath $exePath -WorkingDirectory $AppDir } } catch { }
    exit 2
}
