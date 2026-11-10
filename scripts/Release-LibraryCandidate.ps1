param(
    [Parameter(Mandatory)][ValidateSet('Publish','Verify','Approve','Merge','Confirm','Status')][string]$Action,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')][string]$ProjectId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$CandidateSha,
    [string]$CandidateRepository,
    [long]$RunId,
    [string]$ApprovalId,
    [string]$ControlUrl
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')
$ControlUrl = Resolve-JddaControlUrl $ControlUrl
$controlIsLoopback = ([uri]$ControlUrl).IsLoopback
$headers = @{
    Authorization = 'Bearer ' + (Get-JddaAdminToken -ControlUrl $ControlUrl)
}
$api = "$ControlUrl/api/v1/projects/$ProjectId/library"
$candidateUrl = "$api/integration-candidates/$CandidateSha"
$candidate = Invoke-RestMethod -NoProxy:$controlIsLoopback -MaximumRedirection 0 $candidateUrl -Headers $headers -TimeoutSec 15

function Send-ReleaseRequest([string]$Uri, [hashtable]$Body) {
    $headers['Idempotency-Key'] = [Guid]::NewGuid().ToString('N')
    Invoke-RestMethod -NoProxy:$controlIsLoopback -MaximumRedirection 0 -Method Post -Uri $Uri -Headers $headers -TimeoutSec 75 -ContentType application/json `
        -Body ($Body | ConvertTo-Json -Compress)
}

function Invoke-ReleaseGit([string[]]$GitArgs) {
    $output = & git -C $gitPath -c core.hooksPath=/dev/null @GitArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Git $($GitArgs[0]) failed: $($output -join [Environment]::NewLine)" }
    $output
}

if ($Action -eq 'Status') { return Invoke-RestMethod -NoProxy:$controlIsLoopback -MaximumRedirection 0 "$candidateUrl/release" -Headers $headers -TimeoutSec 15 }
if ($Action -in @('Verify','Approve')) {
    if ($RunId -lt 1) { throw 'Specify the successful GitHub Actions run ID.' }
    if ($Action -eq 'Verify') {
        return Send-ReleaseRequest "$api/repositories/$($candidate.repoId)/candidates/$CandidateSha/verify-ci" @{runId=$RunId}
    }
    return Send-ReleaseRequest "$candidateUrl/release/approve" @{runId=$RunId}
}
if ($Action -eq 'Confirm') {
    if (-not $ApprovalId) { throw 'Specify the approval ID to reconcile.' }
    return Send-ReleaseRequest "$candidateUrl/release/confirm" @{approvalId=$ApprovalId}
}

if (-not $CandidateRepository) { throw 'Specify the local candidate repository.' }
$gitPath = (Resolve-Path -LiteralPath $CandidateRepository).ProviderPath
if ((Invoke-ReleaseGit @('rev-parse','--verify',($CandidateSha+'^{commit}'))) -cne $CandidateSha) {
    throw 'The local repository does not contain the exact candidate commit.'
}
Invoke-ReleaseGit @('merge-base','--is-ancestor',$candidate.baseSha,$CandidateSha) | Out-Null
$binding = Invoke-RestMethod -NoProxy:$controlIsLoopback -MaximumRedirection 0 "$api/repositories/$($candidate.repoId)" -Headers $headers -TimeoutSec 15
$remote = 'git@github.com:' + $binding.slug + '.git'

if ($Action -eq 'Publish') {
    $ref = "refs/heads/jdda/candidates/$CandidateSha"
    $existing = @(Invoke-ReleaseGit @('ls-remote','--refs',$remote,$ref))
    if ($existing.Count -eq 0) {
        Invoke-ReleaseGit @('push',("--force-with-lease="+$ref+":"),$remote,($CandidateSha+':'+$ref)) | Out-Host
    } elseif (($existing[0] -split '\s+')[0] -cne $CandidateSha) {
        throw 'Candidate ref is occupied by another commit.'
    }
    return [pscustomobject]@{repository=$binding.slug;candidateSha=$CandidateSha;candidateRef=$ref}
}

if (-not $ApprovalId) { throw 'Specify the approval ID for this candidate.' }
$permit = Send-ReleaseRequest "$candidateUrl/release/begin" @{approvalId=$ApprovalId}
if ($permit.candidateSha -cne $CandidateSha -or $permit.baseSha -cne $candidate.baseSha `
        -or $permit.repository -cne $binding.slug -or $permit.targetRef -cne $candidate.targetRef `
        -or $permit.status -cne 'MERGING' -or [DateTimeOffset]$permit.expiresAt -le [DateTimeOffset]::UtcNow) {
    throw 'The merge permit no longer matches the reviewed candidate.'
}
Invoke-ReleaseGit @('check-ref-format',$permit.targetRef) | Out-Null
$lease = '--force-with-lease=' + $permit.targetRef + ':' + $permit.baseSha
Invoke-ReleaseGit @('push',$lease,$remote,($CandidateSha+':'+$permit.targetRef)) | Out-Host
for ($attempt = 1; $attempt -le 6; $attempt++) {
    try { return Send-ReleaseRequest "$candidateUrl/release/confirm" @{approvalId=$ApprovalId} }
    catch {
        if ($attempt -eq 6 -or $_.ErrorDetails.Message -notmatch '"code"\s*:\s*"TARGET_CHANGED"') { throw }
        $head = Invoke-ReleaseGit @('ls-remote','--refs',$remote,$permit.targetRef)
        if ($head -cne ($CandidateSha + [char]9 + $permit.targetRef)) { throw }
        Start-Sleep -Seconds 5
    }
}
