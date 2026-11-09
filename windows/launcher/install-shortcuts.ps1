param(
    [string]$InstallDir = "D:\mousemaster"
)

$ErrorActionPreference = "Stop"
$InstallDir = (Resolve-Path $InstallDir).Path
$launcherCs = Join-Path $PSScriptRoot "MousemasterLauncher.cs"
$icon = Join-Path $PSScriptRoot "..\..\src\main\resources\icons\mouse.ico"
if (Test-Path $icon) { $icon = (Resolve-Path $icon).Path }
$exe = Join-Path $InstallDir "Mousemaster.exe"
$csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"

if (-not (Test-Path $csc)) { throw "csc.exe not found: $csc" }
if (-not (Test-Path $launcherCs)) { throw "launcher source not found: $launcherCs" }
if (-not (Test-Path (Join-Path $InstallDir "runtime\bin\javaw.exe"))) { throw "javaw.exe not found under $InstallDir" }

$compile = @("/nologo", "/target:winexe", "/out:$exe", $launcherCs)
if (Test-Path $icon) { $compile = @("/nologo", "/target:winexe", "/win32icon:$icon", "/out:$exe", $launcherCs) }
& $csc @compile
if ($LASTEXITCODE -ne 0) { throw "launcher compile failed: $LASTEXITCODE" }

$layers = "HKCU:\Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers"
if (Test-Path $layers) {
    $existing = Get-ItemProperty $layers
    foreach ($property in $existing.PSObject.Properties) {
        if ($property.Name -eq $exe -and "$($property.Value)" -match "RUNASADMIN") {
            Remove-ItemProperty -Path $layers -Name $property.Name
            Write-Output "Removed RunAs compatibility flag: $($property.Name)"
        }
    }
}

function New-MousemasterShortcut([string]$Path, [bool]$RunAsAdmin) {
    $folder = Split-Path $Path
    if (-not (Test-Path $folder)) { New-Item -ItemType Directory -Path $folder | Out-Null }
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($Path)
    $shortcut.TargetPath = $exe
    $shortcut.Arguments = ""
    $shortcut.WorkingDirectory = $InstallDir
    $shortcut.WindowStyle = 7
    $shortcut.Description = "Mousemaster"
    $shortcut.IconLocation = "$exe,0"
    $shortcut.Save()
    if ($RunAsAdmin) {
        $bytes = [System.IO.File]::ReadAllBytes($Path)
        $flags = [BitConverter]::ToUInt32($bytes, 0x14) -bor 0x2000
        [Array]::Copy([BitConverter]::GetBytes([uint32]$flags), 0, $bytes, 0x14, 4)
        [System.IO.File]::WriteAllBytes($Path, $bytes)
    }
}

$desktop = [Environment]::GetFolderPath("Desktop")
$programs = [Environment]::GetFolderPath("Programs")
$startup = [Environment]::GetFolderPath("Startup")
New-MousemasterShortcut (Join-Path $InstallDir "Mousemaster.lnk") $true
New-MousemasterShortcut (Join-Path $desktop "Mousemaster.lnk") $true
New-MousemasterShortcut (Join-Path $programs "Mousemaster.lnk") $true
New-MousemasterShortcut (Join-Path $startup "Mousemaster.lnk") $false

Write-Output "EXE=$exe"
Write-Output "DESKTOP=$(Join-Path $desktop 'Mousemaster.lnk')"
Write-Output "STARTMENU=$(Join-Path $programs 'Mousemaster.lnk')"
Write-Output "STARTUP=$(Join-Path $startup 'Mousemaster.lnk')"
