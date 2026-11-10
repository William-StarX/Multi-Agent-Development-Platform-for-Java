param([string]$ControlUrl)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')
$root = Resolve-JddaControlUrl $ControlUrl
$token = Get-JddaAdminToken -ControlUrl $root
$loopback = ([uri]$root).IsLoopback
$pairing = Invoke-RestMethod -Method Post -Uri "$root/_console/pairings" -Headers @{ Authorization = "Bearer $token" } `
    -NoProxy:$loopback -MaximumRedirection 0 -TimeoutSec 8
if ($pairing.code -cnotmatch '^[A-Za-z0-9_-]{43}$') { throw '控制服务没有返回有效的配对码。' }
Invoke-JddaWithoutModelSecrets {
    Start-Process "$root/#pair=$($pairing.code)"
} -Additional @('JDDA_ADMIN_TOKEN', 'JDDA_DB_PASSWORD', 'JDDA_WORKER_TOKEN', 'SPRING_DATASOURCE_PASSWORD', 'PGPASSWORD',
    'JDDA_NACOS_USERNAME', 'JDDA_NACOS_PASSWORD')
