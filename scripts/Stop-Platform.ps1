$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')
$projectRoot=Split-Path -Parent $PSScriptRoot
$expected=Join-Path $projectRoot 'platform/control-center/target/control-center-0.1.0-SNAPSHOT.jar'
$services=@(Get-JddaServiceProcess $expected)
if(-not $services.Count){return}
Stop-Process -Id $services.ProcessId
Wait-Process -Id $services.ProcessId -Timeout 15 -ErrorAction SilentlyContinue
Write-Host 'Control center stopped.'
