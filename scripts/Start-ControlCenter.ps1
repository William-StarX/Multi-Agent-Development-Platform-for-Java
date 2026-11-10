param([switch]$Build)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$modulePath = Join-Path $projectRoot 'platform/control-center'
$jarPath = Join-Path $modulePath 'target/control-center-0.1.0-SNAPSHOT.jar'
$previousDatabasePassword = $env:JDDA_DB_PASSWORD
$previousAdminToken = $env:JDDA_ADMIN_TOKEN

if ($Build) {
    Invoke-JddaWithoutModelSecrets {
        & mvn -B -ntp -f (Join-Path $modulePath 'pom.xml') package
        if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    }
}
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Build the control center first, or pass -Build.' }
if (-not $env:JAVA_HOME) { throw 'JAVA_HOME must point to JDK 21.' }

try {
    $env:JDDA_DB_PASSWORD = Get-JddaDatabasePassword
    $env:JDDA_ADMIN_TOKEN = Get-JddaAdminToken -Create
    Invoke-JddaWithoutModelSecrets {
        & "$env:JAVA_HOME/bin/java.exe" -jar $jarPath
        if ($LASTEXITCODE -ne 0) { throw 'Control center exited with an error.' }
    }
} finally {
    $env:JDDA_DB_PASSWORD = $previousDatabasePassword
    $env:JDDA_ADMIN_TOKEN = $previousAdminToken
}
