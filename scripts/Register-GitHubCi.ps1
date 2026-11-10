param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')][string]$ProjectId,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')][string]$RepoId,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+$')][string]$Slug,
    [Parameter(Mandatory)][ValidateRange(1,9007199254740991)][long]$WorkflowId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$WorkflowBlobSha,
    [ValidatePattern('^\.github/workflows/[A-Za-z0-9_-]+\.ya?ml$')][string]$WorkflowPath = '.github/workflows/verify.yml',
    [string]$ControlUrl
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')
$ControlUrl=Resolve-JddaControlUrl $ControlUrl
$controlIsLoopback=([uri]$ControlUrl).IsLoopback
$token=Get-JddaAdminToken -ControlUrl $ControlUrl
$binding=[ordered]@{slug=$Slug; workflowId=$WorkflowId; workflowPath=$WorkflowPath; workflowBlobSha=$WorkflowBlobSha}
$body=$binding | ConvertTo-Json -Compress
$digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($RepoId + $body)))
$headers=@{Authorization="Bearer $token"; 'Idempotency-Key'="bind-ci-$digest"}
$base="$ControlUrl/api/v1/projects/$ProjectId/library/repositories/$RepoId"
Invoke-RestMethod -NoProxy:$controlIsLoopback -MaximumRedirection 0 -Method Post -Uri $base -Headers $headers `
    -ContentType application/json -Body $body -TimeoutSec 30
