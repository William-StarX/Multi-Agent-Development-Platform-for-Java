param([string]$PostgresBin='E:/PostgreSQL/bin')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$backupDirectory=Join-Path $projectRoot 'target/backups'
New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
$backupPath=Join-Path $backupDirectory ('jdda-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.dump')
$previous=$env:PGPASSWORD
try {
    $env:PGPASSWORD=(Import-Clixml (Join-Path $env:LOCALAPPDATA 'JDDA/jdda-local.credential.xml')).GetNetworkCredential().Password
    & (Join-Path $PostgresBin 'pg_dump.exe') -h 127.0.0.1 -U jdda_local -d jdda_local --format=custom --no-owner --no-acl -f $backupPath
    if($LASTEXITCODE -ne 0){throw 'Backup failed'}
} finally { $env:PGPASSWORD=$previous }
[pscustomobject]@{Path=$backupPath;Sha256=(Get-FileHash $backupPath -Algorithm SHA256).Hash.ToLowerInvariant()}
