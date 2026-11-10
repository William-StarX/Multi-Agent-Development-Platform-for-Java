param([switch]$SkipBuild)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')
$projectRoot=Split-Path -Parent $PSScriptRoot
$healthUrl=Get-JddaLocalControlUrl
$jarPath=Join-Path $projectRoot 'platform/control-center/target/control-center-0.1.0-SNAPSHOT.jar'
if(-not $SkipBuild) {
    $null=Get-JddaDatabasePassword
    & (Join-Path $PSScriptRoot 'Stop-Platform.ps1')
    Invoke-JddaWithoutModelSecrets {
        & mvn -B -ntp -f (Join-Path $projectRoot 'pom.xml') package
        if($LASTEXITCODE -ne 0){throw 'Build failed'}
    }
}
$helper=$null
if(Get-JddaServiceProcess $jarPath) {
    Write-Host 'Control center is already running. Restart it after a rebuild to load changes.'
} else {
    $null=Get-JddaDatabasePassword
    if(-not (Test-Path -LiteralPath $jarPath)){throw 'Build the control center before using -SkipBuild.'}
    $logDirectory=Split-Path -Parent $jarPath
    $startScript=Join-Path $PSScriptRoot 'Start-ControlCenter.ps1'
    $helper=Invoke-JddaWithoutModelSecrets {
        Start-Process -FilePath (Get-Command pwsh).Source -ArgumentList @('-NoProfile','-File',('"'+$startScript+'"')) `
            -WindowStyle Hidden -WorkingDirectory $projectRoot -PassThru `
            -RedirectStandardOutput (Join-Path $logDirectory 'platform.log') `
            -RedirectStandardError (Join-Path $logDirectory 'platform.error.log')
    }
}
$ready=$false
for($i=0;$i -lt 30;$i++) {
    try { $health=Invoke-RestMethod "$healthUrl/actuator/health" -NoProxy -TimeoutSec 1; if($health.status -eq 'UP'){$ready=$true;break} } catch {}
    if($helper -and $helper.HasExited){break}
    Start-Sleep -Seconds 1
}
if(-not $ready){throw 'Control center failed to become healthy; inspect target logs.'}
Write-Host "Control center ready: $healthUrl"
Write-Host 'Run scripts/Verify-Platform.ps1 for local tests. Add -WithModel to run the DeepSeek fixture workflow.'
