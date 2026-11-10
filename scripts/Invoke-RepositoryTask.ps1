param(
    [Parameter(Mandatory)][string]$Repository,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$BaseCommit,
    [Parameter(Mandatory)][string]$ProjectId,
    [Parameter(Mandatory)][string]$RepoId,
    [Parameter(Mandatory)][string]$Specification,
    [ValidatePattern('^[A-Z][A-Z0-9]*-[0-9a-f]{8}$')][string]$ResumeTaskId,
    [string]$ControlUrl,
    [string]$WorkerHostId = $env:JDDA_WORKER_HOST_ID
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')
. (Join-Path $PSScriptRoot 'ModelProcess.ps1')
$ControlUrl = Resolve-JddaControlUrl $ControlUrl
$controlIsLoopback = ([uri]$ControlUrl).IsLoopback
$admin = Get-JddaAdminToken -ControlUrl $ControlUrl
if (-not $WorkerHostId) { $WorkerHostId = 'windows-repository-worker' }
$root = Split-Path -Parent $PSScriptRoot
$module = Join-Path $root 'platform/agent-poc'
$source = (Resolve-Path -LiteralPath $Repository).ProviderPath
$spec = Get-Content -LiteralPath $Specification -Raw | ConvertFrom-Json -AsHashtable
$taskId = if ($ResumeTaskId) { $ResumeTaskId } else { $spec.task + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8) }
$run = Join-Path $root "target/repository-tasks/$taskId"
$workspace = Join-Path $run 'source'
New-Item -ItemType Directory -Path $run -Force | Out-Null
$specPath = Join-Path $run 'specification.json'
if ($ResumeTaskId) { $spec = Get-Content -LiteralPath $specPath -Raw | ConvertFrom-Json -AsHashtable }
else { $spec | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $specPath -Encoding utf8 }
$classpath = (Join-Path $module 'target/classes') + ';' + (Join-Path $module 'target/dependency/*')
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$managedCredential = -not $env:JDDA_WORKER_TOKEN
$workerToken = if ($managedCredential) { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
    else { $env:JDDA_WORKER_TOKEN }
$api = "$ControlUrl/api/v1/projects/$([uri]::EscapeDataString($ProjectId))"

function Invoke-TaskApi([string]$Path, [string]$Token, $Body, [string]$Match = '', [string]$Idempotency = '',
        $GuidanceVersion = $null, [switch]$CreateCredential) {
    $headers = @{ Authorization = "Bearer $Token" }
    $request = @{ Uri = $api + $Path; Headers = $headers; NoProxy = $controlIsLoopback; TimeoutSec = 15; MaximumRedirection = 0 }
    if ($null -ne $Body) {
        $headers['Idempotency-Key'] = if ($Idempotency) { $Idempotency } else { [Guid]::NewGuid().ToString('N') }
        if ($Match) { $headers['If-Match'] = $Match }
        if ($CreateCredential) { $headers['If-None-Match'] = '*' }
        if ($null -ne $GuidanceVersion) { $headers['X-JDDA-Instruction-Version'] = [string]$GuidanceVersion }
        $request.Method = 'Post'
        $request.ContentType = 'application/json'
        $request.Body = $Body | ConvertTo-Json -Depth 25 -Compress
    }
    try { Invoke-RestMethod @request } catch {
        $status = [int]$_.Exception.Response.StatusCode
        if ($status -notin @(0, 502, 503, 504)) { throw }
        Start-Sleep -Milliseconds (Get-Random -Minimum 150 -Maximum 350)
        Invoke-RestMethod @request
    }
}

function Invoke-WorkerGit([string]$Directory, [string[]]$Arguments) {
    $output = & git -C $Directory -c core.hooksPath=/dev/null @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Git $($Arguments[0]) failed: $($output -join "`n")" }
    $output
}

function Get-TaskGuidance {
    if (-not $guided) { return @{version=0;items=@()} }
    Invoke-TaskApi "$attemptPath/instructions?epoch=$($lease.leaseEpoch)" $workerToken
}

function Invoke-ModelProcess([string[]]$Arguments, [string]$LogName, [switch]$Heartbeat) {
    $info = [Diagnostics.ProcessStartInfo]::new($java)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in (@('-cp', $classpath) + $Arguments)) { $info.ArgumentList.Add($argument) }
    Set-JddaModelProcessEnvironment $info
    $process = [Diagnostics.Process]::Start($info)
    $output = $process.StandardOutput.ReadToEndAsync()
    $errors = $process.StandardError.ReadToEndAsync()
    $deadline = [DateTime]::UtcNow.AddMinutes(6)
    try {
        while (-not $process.WaitForExit(8000)) {
            if ([DateTime]::UtcNow -gt $deadline) { throw 'Model process timed out.' }
            if ($Heartbeat) {
                Invoke-TaskApi "$attemptPath/heartbeat" $workerToken $lease | Out-Null
                if ($guided -and (Get-TaskGuidance).version -ne $guidanceVersion) { throw 'Operator instruction changed.' }
            }
        }
        @($output.GetAwaiter().GetResult(), $errors.GetAwaiter().GetResult()) |
            Set-Content -LiteralPath (Join-Path $run "$LogName.log") -Encoding utf8
        if ($process.ExitCode -ne 0) { throw "Model failed. See $run/$LogName.log" }
    } finally {
        try {
            if (-not $process.HasExited) {
                $script:workerQuiescent = $false
                $process.Kill($true)
                $script:workerQuiescent = $process.WaitForExit(5000)
                if (-not $script:workerQuiescent) { throw 'The model process has not stopped.' }
            }
        } finally { $process.Dispose() }
    }
}

function Digest([string]$File) { 'sha256:' + (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLowerInvariant() }

$topologyPath = Join-Path $run 'topology.json'
$goalPath = Join-Path $run 'goal.txt'
$planPath = Join-Path $run 'plan.json'
$claimPath = Join-Path $run 'claim.json'
if (-not $ResumeTaskId) {
    Invoke-WorkerGit $source @('clone', '--no-hardlinks', '--no-checkout', '-c', 'core.autocrlf=false', '--', $source, $workspace) | Out-Null
    Invoke-WorkerGit $workspace @('checkout', '--detach', $BaseCommit) | Out-Null
$nodes = @(
    @{id=$spec.manager;parent='root';role='MANAGER';capabilities=@()},
    @{id=$spec.scope;parent=$spec.manager;role='MANAGER';capabilities=@()},
    @{id=$spec.worker;parent=$spec.scope;role='WORKER';capabilities=@($spec.capability)}
)
foreach ($node in $nodes) {
    Invoke-TaskApi '/agents' $admin @{
        schemaVersion='1';projectId=$ProjectId;agentId=$node.id;parentId=$node.parent;roles=@($node.role)
        capabilities=$node.capabilities;endpoint=$null;hostId=$WorkerHostId
    } '' ('repository-node-' + $node.id) | Out-Null
}
$spec.goal | Set-Content -LiteralPath $goalPath -Encoding utf8
Invoke-TaskApi '' $admin | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $topologyPath -Encoding utf8
Invoke-ModelProcess @('io.jdda.poc.DelegationPlanner', $topologyPath, $planPath, $goalPath, $spec.scope, $spec.capability) 'planner'
}
if ((Invoke-WorkerGit $workspace @('rev-parse', 'HEAD')) -cne $BaseCommit -or
        (Invoke-WorkerGit $workspace @('status', '--porcelain'))) { throw 'Worker clone must start clean on the task base.' }
$plan = Get-Content -LiteralPath $planPath -Raw | ConvertFrom-Json -AsHashtable
$task = (Get-Content -LiteralPath (Join-Path $root 'contracts/examples.json') -Raw | ConvertFrom-Json -AsHashtable).TaskPackage
$owned = @($spec.write) + @($spec.remove) + @($spec.move.Keys) + @($spec.move.Values)
$task.projectId = $ProjectId; $task.taskId = $taskId; $task.parentTaskId = $null; $task.planVersion = 1
$task.managerId = $spec.scope; $task.topologyVersion = $plan.topologyVersion; $task.delegation = $plan.delegation
$task.repoId = $RepoId; $task.baseCommit = $BaseCommit; $task.dependencies = @(); $task.ownedPaths = $owned
$task.toolPolicyId = if ($spec.policy) { $spec.policy } else { 'repository-files-v1' }
if ($task.toolPolicyId -cnotin @('repository-files-v1','generated-java-v1')) { throw 'Unsupported repository task policy.' }
$task.requiredCapabilities = @($spec.capability)
$task.operatorGuidance = $true
$task.acceptanceProfile = if ($task.toolPolicyId -ceq 'generated-java-v1') { 'generated-java-source-scope' } else { 'scoped-repository-edit' }
$task.timeoutSeconds = 540; $task.maxAttempts = 1
$worker = $plan.delegation.steps[2].to
$taskPath = "/tasks/$taskId/revisions/1"
$credentialTag = $null
$attemptPath = $null
$submitted = $false
$workerQuiescent = $true
try {
    if ($managedCredential) {
        $credentialTag = '"' + [Convert]::ToHexString(
            [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($workerToken))).ToLowerInvariant() + '"'
        Invoke-TaskApi "/agents/$worker/credentials" $admin @{token=$workerToken} -CreateCredential | Out-Null
    }
    if ($ResumeTaskId) {
        $existing = Invoke-TaskApi $taskPath $admin
        if ($existing.snapshot.state -cne 'READY' -or $existing.task.baseCommit -cne $BaseCommit) {
            throw 'Only ready tasks on this base can be resumed.'
        }
    } else {
        for ($publishTry = 1; $publishTry -le 3; $publishTry++) {
            try {
                Invoke-TaskApi '/task-batches' $admin @{tasks=@($task)} | Out-Null
                break
            } catch {
                $detail = $_.ErrorDetails.Message | ConvertFrom-Json -ErrorAction SilentlyContinue
                if ($detail.code -cne 'VERSION_CONFLICT' -or $publishTry -eq 3) { throw }
                $currentVersion = (Invoke-TaskApi '' $admin).topologyVersion
                if ($currentVersion -eq $task.topologyVersion) { throw }
                $task.topologyVersion = $currentVersion
            }
        }
    }
    $claimDeadline = [DateTime]::UtcNow.AddMinutes(12)
    do {
        try {
            $claim = Invoke-TaskApi "/worker/claims?taskId=$([uri]::EscapeDataString($taskId))&revision=1" $workerToken @{}
        } catch {
            if ([int]$_.Exception.Response.StatusCode -ne 429) { throw }
            $claim = $null
        }
        if (-not $claim) { Start-Sleep -Milliseconds (Get-Random -Minimum 2400 -Maximum 3600) }
    } while (-not $claim -and [DateTime]::UtcNow -lt $claimDeadline)
    if ($claim.task.taskId -cne $taskId -or $claim.task.baseCommit -cne $BaseCommit -or $claim.attempt.workerId -cne $worker) {
        throw 'Unexpected task or base in Worker claim.'
    }
    $guided = $claim.task.operatorGuidance -eq $true
    $attemptPath = "/attempts/$($claim.attempt.attemptId)"
    $lease = @{revision=$claim.attempt.revision;leaseEpoch=$claim.lease.leaseEpoch}
    Invoke-TaskApi "$attemptPath/started" $workerToken $lease | Out-Null
    $guidanceVersion = $null
    while ($true) {
        $instructions = Get-TaskGuidance
        if ($null -ne $guidanceVersion) {
            $workspace = Join-Path $run "source-v$($instructions.version)"
            Invoke-WorkerGit $source @('clone', '--no-hardlinks', '--no-checkout', '-c', 'core.autocrlf=false', '--', $source, $workspace) | Out-Null
            Invoke-WorkerGit $workspace @('checkout', '--detach', $BaseCommit) | Out-Null
        }
        $guidanceVersion = $instructions.version
        $claim | Add-Member -NotePropertyName operatorGuidance -NotePropertyValue $instructions -Force
        $claim | ConvertTo-Json -Depth 25 | Set-Content -LiteralPath $claimPath -Encoding utf8
        $reportPath = Join-Path $run "worker-v$guidanceVersion.json"
        $resultCommit = $null
        $changed = @()
        $failure = $null
        try {
            Invoke-ModelProcess @('io.jdda.poc.RepositoryWorker', $workspace, $claimPath, $specPath, $reportPath) "worker-v$guidanceVersion" -Heartbeat
            if ($spec.jdbcRefactor) {
                & python -B (Join-Path $PSScriptRoot 'Apply-JdbcRefactor.py') $workspace $specPath $reportPath
                if ($LASTEXITCODE -ne 0) { throw 'Scoped JDBC layer extraction failed.' }
            }
            Invoke-WorkerGit $workspace (@('add','-A','--') + $owned) | Out-Null
            $changed = @(Invoke-WorkerGit $workspace @('diff','--cached','--name-only'))
            if (-not $changed.Count -or @($changed | Where-Object { $_ -cnotin $owned }).Count) { throw 'Worker changed paths outside its task.' }
            Invoke-WorkerGit $workspace @('diff','--cached','--check') | Out-Null
            Invoke-WorkerGit $workspace @('-c',"user.name=JDDA $worker",'-c','user.email=worker@jdda.invalid','-c','commit.gpgSign=false',
                'commit','-m',"JDDA $taskId") | Out-Null
            $resultCommit = Invoke-WorkerGit $workspace @('rev-parse','HEAD')
            if ((Invoke-WorkerGit $workspace @('rev-list','--parents','-n','1','HEAD')) -cne "$resultCommit $BaseCommit" `
                    -or (Invoke-WorkerGit $workspace @('status','--porcelain'))) { throw 'Unexpected Worker Git result.' }
        } catch { $failure = $_.Exception.Message }

        if (-not $workerQuiescent) { throw 'Cannot submit a result while the model process is still running.' }
        if ($guided -and (Get-TaskGuidance).version -ne $guidanceVersion) { continue }
        $artifactPath = if ($failure) { Join-Path $run "failure-v$guidanceVersion.txt" } else { $reportPath }
        if ($failure) { $failure | Set-Content -LiteralPath $artifactPath -Encoding utf8 }
        $artifact = @{artifactId='worker-report';uri=([uri]$artifactPath).AbsoluteUri;digest=(Digest $artifactPath)}
        $manifestPath = Join-Path $run 'manifest.json'
        $manifest = @{taskId=$taskId;revision=1;repository=$workspace;baseCommit=$BaseCommit;resultCommit=$resultCommit
            projectId=$ProjectId;worker=$worker;attemptId=$claim.attempt.attemptId;artifact=$artifact;changedPaths=$changed}
        if ($guided) { $manifest.guidanceVersion = $guidanceVersion }
        $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8
        $digest = Digest $manifestPath
        $submission = @{
            schemaVersion='1';projectId=$ProjectId;taskId=$taskId;revision=1;attemptId=$claim.attempt.attemptId
            leaseEpoch=$lease.leaseEpoch;resultCommit=$(if (-not $failure) {$resultCommit});resultDigest=$digest
            outcome=$(if ($failure) {'FAILED'} else {'PASSED'});manifestUri=([uri]$manifestPath).AbsoluteUri;evidence=@($artifact)
        }
        try {
            $receipt = Invoke-TaskApi "$attemptPath/results" $workerToken $submission -GuidanceVersion $(if ($guided) { $guidanceVersion })
        } catch {
            $detail = $_.ErrorDetails.Message | ConvertFrom-Json -ErrorAction SilentlyContinue
            if ($guided -and $detail.code -ceq 'INSTRUCTION_UPDATED' -and (Get-TaskGuidance).version -gt $guidanceVersion) { continue }
            throw
        }
        $submitted = $true
        break
    }
    $readback = Invoke-TaskApi $taskPath $admin
    if ($receipt.status -cne 'VERIFYING' -or $readback.snapshot.state -cne 'VERIFYING' -or (Digest $artifactPath) -cne $artifact.digest) {
        throw 'Worker evidence did not match readback.'
    }
    $state = Invoke-TaskApi "$taskPath/verification" $admin @{accepted=(-not $failure);resultDigest=$digest} `
        ('"' + $readback.snapshot.rowVersion + '"')
    if ($failure -or $state.state -cne 'SUCCEEDED') { throw "Task $taskId failed: $failure" }
    [pscustomobject]($manifest + @{state=$state.state;modelReport=$reportPath;plannerReport=$planPath;manifest=$manifestPath})
} finally {
    if ($attemptPath -and -not $submitted -and $workerQuiescent) {
        try {
            $stopPath = Join-Path $run 'stopped.txt'
            "Worker stopped for attempt $($claim.attempt.attemptId)." | Set-Content -LiteralPath $stopPath -Encoding utf8
            $stopEvidence = @{artifactId='worker-stop';uri=([uri]$stopPath).AbsoluteUri;digest=(Digest $stopPath)}
            Invoke-TaskApi "$attemptPath/stopped" $workerToken ($lease + @{evidence=@($stopEvidence)}) | Out-Null
        } catch { Write-Warning 'Could not confirm Worker stop with the control service. Check the task state before retrying.' }
    }
    if ($credentialTag) {
        try { Invoke-TaskApi "/agents/$worker/credentials/revoke" $admin @{} $credentialTag | Out-Null }
        catch {
            $detail = $_.ErrorDetails.Message | ConvertFrom-Json -ErrorAction SilentlyContinue
            if ($detail.code -ne 'CREDENTIAL_CHANGED') { Write-Warning 'Could not confirm temporary Worker credential revocation.' }
        }
    }
}
