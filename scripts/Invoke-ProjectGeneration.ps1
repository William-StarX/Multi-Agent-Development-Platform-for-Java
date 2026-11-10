param(
    [Parameter(Mandatory)][string]$Manifest,
    [Parameter(Mandatory)][string]$Output,
    [string]$ControlUrl,
    [switch]$Minimal,
    [switch]$Resume,
    [string]$Repair
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')
$ControlUrl = Resolve-JddaControlUrl $ControlUrl
$token = Get-JddaAdminToken -ControlUrl $ControlUrl
$outputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Output)
$run = $outputPath + '.jdda'
if (-not $Resume -and ((Test-Path -LiteralPath $outputPath) -or (Test-Path -LiteralPath $run))) {
    throw 'Use a new output path for a generated Git repository and its evidence.'
}
if ($Repair -and -not $Resume) { throw 'Repair requires -Resume and an existing candidate.' }

function Invoke-ProjectGit([string[]]$Arguments) {
    $result = & git -C $outputPath -c core.hooksPath=/dev/null -c core.autocrlf=false @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Git $($Arguments[0]) failed: $($result -join [Environment]::NewLine)" }
    $result
}

$planPath = Join-Path $run 'work-plan.json'
$render = @('-B', (Join-Path $PSScriptRoot 'Generate-Project.py'), '--manifest', $Manifest)
if ($Minimal) { $render += '--minimal' }
$approvedPlan = & python @render --plan-only
if ($LASTEXITCODE -ne 0) { throw 'Invalid project generation manifest.' }
$plan = $approvedPlan | ConvertFrom-Json
if (-not $plan.tasks.Count) { throw 'An Agent generation run needs at least one approved task.' }
$notes = @{}
if ($Repair) {
    $notes = Get-Content -LiteralPath $Repair -Raw | ConvertFrom-Json -AsHashtable
    if ($notes -isnot [System.Collections.IDictionary] -or -not $notes.Count) {
        throw 'Repair must map one or more approved task IDs to diagnostics.'
    }
    foreach ($id in $notes.Keys) {
        if ($id -cnotin $plan.tasks.task -or $notes[$id] -isnot [string] -or
                [string]::IsNullOrWhiteSpace($notes[$id]) -or $notes[$id].Length -gt 3000) {
            throw 'Each repair note must be nonempty and target an approved task ID.'
        }
    }
}
$manifestHash = (Get-FileHash -LiteralPath $Manifest -Algorithm SHA256).Hash.ToLowerInvariant()
if (-not $Resume) {
    & python @render --output $outputPath --task-plan $planPath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Project baseline generation failed.' }
}
$reportPath = Join-Path $run 'generation.json'
function Save-GenerationReport {
    $temporary = $reportPath + '.tmp'
    $report | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $temporary -Encoding utf8
    [IO.File]::Move($temporary, $reportPath, $true)
}
$lock = [IO.File]::Open((Join-Path $run 'generation.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    if ($Resume) {
        $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json -AsHashtable
        if ($report.manifestSha256 -cne $manifestHash -or $report.minimal -ne $Minimal.IsPresent -or
                $report.repository -cne $outputPath -or (Invoke-ProjectGit @('rev-parse','refs/heads/main')) -cne $report.baseSha -or
                (Invoke-ProjectGit @('status','--porcelain'))) {
            throw 'Resume requires the original manifest, options and clean Git baseline.'
        }
        if ($report.tasks.Count -gt $plan.tasks.Count) { throw 'Invalid previous generation report.' }
        for ($i = 0; $i -lt $report.tasks.Count; $i++) {
            $prior = $report.tasks[$i]
            if ($prior.state -cne 'SUCCEEDED' -or $prior.baseCommit -cne $report.baseSha -or
                    $prior.worker -cne $plan.tasks[$i].worker -or -not $prior.taskId.StartsWith($plan.tasks[$i].task + '-')) {
                throw 'Previous results do not match the approved task prefix.'
            }
        }
        if ($report.candidateSha -and -not $Repair) { return Get-Content -LiteralPath $reportPath -Raw }
        if ($Repair -and (-not $report.candidateSha -or $report.tasks.Count -ne $plan.tasks.Count)) {
            throw 'Repair requires an existing complete Library candidate.'
        }
    } else {
        Invoke-ProjectGit @('init','--initial-branch=main','--object-format=sha1','--template=') | Out-Null
        Invoke-ProjectGit @('add','--','.') | Out-Null
        Invoke-ProjectGit @('-c','user.name=JDDA Baseline','-c','user.email=baseline@jdda.invalid',
            '-c','commit.gpgSign=false','commit','-m','JDDA trusted project baseline') | Out-Null
        $projectId = $plan.project + '-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
        $headers = @{Authorization = "Bearer $token"; 'Idempotency-Key' = "bootstrap-$projectId"}
        Invoke-RestMethod -Method Post -Uri "$ControlUrl/api/v1/projects" -Headers $headers `
            -NoProxy:(([uri]$ControlUrl).IsLoopback) -MaximumRedirection 0 -ContentType application/json -TimeoutSec 15 `
            -Body (@{projectId=$projectId} | ConvertTo-Json -Compress) | Out-Null
        $report = [ordered]@{projectId=$projectId; repoId=$plan.project; repository=$outputPath;
            baseSha=(Invoke-ProjectGit @('rev-parse','HEAD')); minimal=[bool]$Minimal; manifestSha256=$manifestHash; tasks=@()}
        Save-GenerationReport
    }

    for ($i = 0; $i -lt $plan.tasks.Count; $i++) {
        $task = $plan.tasks[$i]
        $replacing = $notes.Contains($task.task)
        if ($i -lt $report.tasks.Count -and -not $replacing) { continue }
        $suffix = if ($replacing) { '-repair-' + [Guid]::NewGuid().ToString('N').Substring(0,8) } else { '' }
        if ($replacing) { $task.constraints += "`nApproved acceptance diagnostics: " + $notes[$task.task] }
        $specPath = Join-Path $run ($task.task + $suffix + '.json')
        $task | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $specPath -Encoding utf8
        Write-Host "Generating $($task.task) for $($task.scope)"
        $result = & (Join-Path $PSScriptRoot 'Invoke-RepositoryTask.ps1') -Repository $outputPath -BaseCommit $report.baseSha `
            -ProjectId $report.projectId -RepoId $plan.project -Specification $specPath -ControlUrl $ControlUrl
        if ($result.state -cne 'SUCCEEDED' -or $result.baseCommit -cne $report.baseSha) { throw 'Unexpected Worker result.' }
        if ($replacing) { $report.tasks[$i] = $result }
        else {
            $report.tasks += $result
            Save-GenerationReport
        }
    }
    $sources = @($report.tasks | ForEach-Object { @{taskId=$_.taskId;revision=$_.revision;repository=$_.repository} })
    $candidate = & (Join-Path $PSScriptRoot 'New-LibraryCandidate.ps1') -ProjectId $report.projectId -TargetRepository $outputPath `
        -TargetRef 'refs/heads/main' -ExpectedBaseSha $report.baseSha -Sources $sources -ControlUrl $ControlUrl
    if ($Repair) {
        $previous = Join-Path $run ("generation-" + $report.candidateSha + ".json")
        if (-not (Test-Path -LiteralPath $previous)) { Copy-Item -LiteralPath $reportPath -Destination $previous }
    }
    $report.candidateSha = $candidate.Record.candidateSha
    $report.candidateRepository = $candidate.Repository
    $report.changedPaths = $candidate.ChangedPaths
    Save-GenerationReport
    Write-Host "Candidate registered. Review and verify the exact SHA before promotion."
    Get-Content -LiteralPath $reportPath -Raw
} finally { $lock.Dispose() }
