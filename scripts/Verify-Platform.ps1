param([switch]$WithModel)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$python=Join-Path $projectRoot 'contracts/.venv/Scripts/python.exe'
if(-not (Test-Path -LiteralPath $python)){$python=(Get-Command python -ErrorAction Stop).Source}
$previousPassword=$env:JDDA_DB_PASSWORD
$previousUrl=$env:JDDA_TEST_DB_URL
try {
    if(-not $env:JDDA_DB_PASSWORD) {
        $env:JDDA_DB_PASSWORD=(Import-Clixml (Join-Path $env:LOCALAPPDATA 'JDDA/jdda-local.credential.xml')).GetNetworkCredential().Password
    }
    if(-not $env:JDDA_TEST_DB_URL){$env:JDDA_TEST_DB_URL='jdbc:postgresql://127.0.0.1:5432/jdda_test'}
    & mvn -B -ntp -f (Join-Path $projectRoot 'pom.xml') test
    if($LASTEXITCODE -ne 0){throw 'Java validation failed'}
    & $python -B -m unittest discover -s (Join-Path $projectRoot 'contracts') -p 'test_*.py' -v
    if($LASTEXITCODE -ne 0){throw 'Contract validation failed'}
} finally {
    $env:JDDA_DB_PASSWORD=$previousPassword
    $env:JDDA_TEST_DB_URL=$previousUrl
}
if($WithModel) { & (Join-Path $PSScriptRoot 'Test-AgentPoc.ps1') -SkipBuild -Runs 1 -Plan }
