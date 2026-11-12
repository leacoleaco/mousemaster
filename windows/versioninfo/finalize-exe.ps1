# After native-image links mousemaster.exe it is still a console-subsystem program, so
# Windows opens a terminal on the taskbar even when the tray hides that window.
# This switches the PE subsystem to WINDOWS (no console at startup) and embeds the
# requireAdministrator manifest. native-image has no reliable switch for either.
param(
    [Parameter(Mandatory)] [string] $exe,
    [string] $manifest
)
$ErrorActionPreference = "Stop"
if (-not $manifest) {
    $manifest = Join-Path $PSScriptRoot "mousemaster.manifest"
}

function Set-PeSubsystemWindows([string] $path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $pe = [BitConverter]::ToInt32($bytes, 0x3C)
    $magic = [BitConverter]::ToUInt16($bytes, $pe + 24)
    if ($magic -ne 0x20B) { throw "Expected a PE32+ executable: $path" }
    $subsystem = $pe + 24 + 68
    [BitConverter]::GetBytes([uint16] 2).CopyTo($bytes, $subsystem)
    $checksumAt = $pe + 24 + 64
    [BitConverter]::GetBytes([uint32] 0).CopyTo($bytes, $checksumAt)
    $sum = 0L
    for ($i = 0; $i -lt $bytes.Length; $i += 2) {
        $word = [int64] $bytes[$i]
        if ($i + 1 -lt $bytes.Length) { $word += [int64] $bytes[$i + 1] -shl 8 }
        $sum = ($sum -band 0xFFFF) + ($sum -shr 16) + $word
    }
    $sum = ($sum -band 0xFFFF) + ($sum -shr 16)
    $sum += $bytes.Length
    [BitConverter]::GetBytes([uint32] $sum).CopyTo($bytes, $checksumAt)
    [System.IO.File]::WriteAllBytes($path, $bytes)
}

if (-not (Test-Path -LiteralPath $exe)) { throw "Executable not found: $exe" }
if (-not (Test-Path -LiteralPath $manifest)) { throw "Manifest not found: $manifest" }

# native-image has just closed the file, and Defender often scans it immediately.
# mt.exe and editbin update it in place, which fails while that handle is still open.
# A copy launched from this path keeps the image mapped and has to be stopped first.
Get-Process -Name mousemaster -ErrorAction SilentlyContinue | ForEach-Object {
    $running = $_.Path
    if ($running -and [string]::Equals($running, $exe, [StringComparison]::OrdinalIgnoreCase)) {
        Write-Output "Stopping mousemaster.exe that has the new executable open (pid $($_.Id))"
        Stop-Process -Id $_.Id -Force
    }
}

function Wait-FileWritable([string] $path) {
    $attempts = 15
    for ($try = 1; $try -le $attempts; $try++) {
        try {
            $stream = [System.IO.File]::Open($path, 'Open', 'ReadWrite', 'None')
            $stream.Close()
            return
        }
        catch {
            if ($try -eq $attempts) { throw "Executable is still in use: $path" }
            Write-Output "Executable is in use, waiting ($try/$attempts)..."
            Start-Sleep -Seconds 2
        }
    }
}

$mt = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\mt.exe" -ErrorAction SilentlyContinue |
    Sort-Object FullName | Select-Object -Last 1
if (-not $mt) { throw "mt.exe not found in the Windows SDK" }
$mtExit = 1
for ($try = 1; $try -le 15; $try++) {
    Wait-FileWritable $exe
    & $mt.FullName -nologo -manifest $manifest -outputresource:"${exe};#1"
    $mtExit = $LASTEXITCODE
    if ($mtExit -eq 0) { break }
    # 31 is mt.exe's sharing violation: the new executable is still open.
    if ($mtExit -ne 31 -or $try -eq 15) { throw "mt.exe failed with exit code $mtExit" }
    Write-Output "mt.exe could not update the executable because it is in use. Retrying ($try/15)..."
    Start-Sleep -Seconds 2
}

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$editbin = $null
if (Test-Path -LiteralPath $vswhere) {
    $editbin = & $vswhere -latest -products * -find "VC\Tools\MSVC\*\bin\Hostx64\x64\editbin.exe" |
        Select-Object -Last 1
}
if ($editbin) {
    Wait-FileWritable $exe
    & $editbin /NOLOGO /SUBSYSTEM:WINDOWS $exe
    if ($LASTEXITCODE -ne 0) { throw "editbin failed with exit code $LASTEXITCODE" }
} else {
    Set-PeSubsystemWindows $exe
}
Write-Output "Finalized $exe (WINDOWS subsystem, requireAdministrator)"
