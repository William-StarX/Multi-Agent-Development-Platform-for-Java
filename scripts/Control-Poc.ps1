# Trusted coordinator functions. Dot-sourced only by Test-AgentPoc.ps1.
function Invoke-ControlApi([string]$Path, $Body, [string]$Token, [string]$Version = '') {
    $headers = @{Authorization="Bearer $Token"; 'Idempotency-Key'=[Guid]::NewGuid().ToString('N')}
    if ($Version) { $headers['If-Match']=$Version }
    Invoke-RestMethod -NoProxy:$controlIsLoopback -MaximumRedirection 0 -Method Post -Uri ($ControlUrl + '/api/v1' + $Path) -Headers $headers `
        -ContentType application/json -Body ($Body | ConvertTo-Json -Depth 30) -TimeoutSec 15
}
function Read-ControlApi([string]$Path) {
    Invoke-RestMethod -NoProxy:$controlIsLoopback -MaximumRedirection 0 -Uri ($ControlUrl + '/api/v1' + $Path) `
        -Headers @{Authorization="Bearer $adminToken"} -TimeoutSec 15
}
function Initialize-ControlPoc {
    $script:adminToken=Get-JddaAdminToken -ControlUrl $ControlUrl
    $script:controlProject='poc-'+[Guid]::NewGuid().ToString('N')
    Invoke-ControlApi '/projects' @{projectId=$controlProject} $adminToken | Out-Null
    $nodes = @(@{id='middle';parent='root';role='MANAGER'},@{id='scope';parent='middle';role='MANAGER'},@{id='worker';parent='scope';role='WORKER'})
    if ($Plan) { $nodes += @{id='worker-reserve';parent='scope';role='WORKER'} }
    foreach ($node in $nodes) {
        Invoke-ControlApi "/projects/$controlProject/agents" @{
            schemaVersion='1';projectId=$controlProject;agentId=$node.id;parentId=$node.parent;roles=@($node.role)
            capabilities=@('java','maven');endpoint=$null;hostId="host-$controlProject"
        } $adminToken | Out-Null
    }
    if (-not $Plan) { Set-ControlWorker 'worker' }
    if ($Bugfix) {
        $script:bugfixToken=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        Invoke-ControlApi "/projects/$controlProject/agents/bugfix/credentials" `
            @{token=$bugfixToken;hostId="host-$controlProject";capabilities=@('java','maven')} $adminToken | Out-Null
    }
}
function Get-ControlTopology {
    Invoke-RestMethod -NoProxy:$controlIsLoopback -MaximumRedirection 0 -Uri "$ControlUrl/api/v1/projects/$controlProject" `
        -Headers @{Authorization="Bearer $adminToken"} -TimeoutSec 15
}
function Set-ControlWorker([string]$WorkerId) {
    if ($WorkerId -ceq $controlWorker) { return }
    if ($controlWorker) {
        Invoke-ControlApi "/projects/$controlProject/agents/$controlWorker/credentials/revoke" @{} $adminToken | Out-Null
    }
    $script:controlWorker=$null
    $script:workerToken=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    Invoke-ControlApi "/projects/$controlProject/agents/$WorkerId/credentials" @{token=$workerToken} $adminToken | Out-Null
    $script:controlWorker=$WorkerId
}
function Start-ControlTask([int]$RunNumber,[string]$BaseSha,$Planning) {
    $task=Get-Content (Join-Path $projectRoot 'contracts/examples.json') -Raw | ConvertFrom-Json -AsHashtable
    $task=$task.TaskPackage
    $task.projectId=$controlProject;$task.taskId="FIX-$RunNumber";$task.managerId='scope';$task.topologyVersion=4
    $task.parentTaskId=$null;$task.dependencies=@();$task.baseCommit=$BaseSha;$task.ownedPaths=@('src/main/java/demo/PriceCalculator.java')
    $task.repoId='local-fixture';$task.acceptanceProfile='fixture-six-tests';$task.timeoutSeconds=300;$task.maxAttempts=1
    if ($Bugfix) { $task.maxAttempts=2 }
    $task.budget.tokenBudget=8000;$task.budget.costBudget.limit='1.00'
    if ($Planning) {
        $task.topologyVersion=$Planning.topologyVersion
        $task.delegation=$Planning.delegation
        $task.managerId=$Planning.delegation.steps[2].from
    }
    Invoke-ControlApi "/projects/$controlProject/tasks" $task $adminToken | Out-Null
    if ($Planning) { Set-ControlWorker $Planning.delegation.steps[2].to }
    Invoke-ControlApi "/projects/$controlProject/tasks/FIX-$RunNumber/revisions/1/publish" @{} $adminToken '"1"' | Out-Null
    $script:claim=Invoke-ControlApi "/projects/$controlProject/worker/claims" @{} $workerToken
    if ($claim.task.taskId -cne "FIX-$RunNumber" -or $claim.task.baseCommit -cne $BaseSha `
            -or $claim.attempt.workerId -cne $controlWorker) { throw 'Unexpected claimed task or Worker.' }
    Start-ControlAttempt $workerToken
}
function Start-ControlAttempt([string]$Token) {
    $script:attemptActorToken=$Token
    $script:attemptPath="/projects/$controlProject/attempts/$($claim.attempt.attemptId)"
    $script:attemptToken=@{revision=$claim.attempt.revision;leaseEpoch=$claim.lease.leaseEpoch}
    Invoke-ControlApi "$attemptPath/started" $attemptToken $attemptActorToken | Out-Null
}
function Send-ControlHeartbeat {
    Invoke-ControlApi "$attemptPath/heartbeat" $attemptToken $attemptActorToken | Out-Null
}
function Move-ControlTaskToBugfix([int]$RunNumber) {
    $testReport=Join-Path $baseline 'target/surefire-reports/TEST-demo.PriceCalculatorTest.xml'
    $evidence=@{artifactId="baseline-tests-$RunNumber";uri=([uri]$testReport).AbsoluteUri;
        digest='sha256:'+(Get-FileHash -LiteralPath $testReport -Algorithm SHA256).Hash.ToLowerInvariant()}
    $manifest=Join-Path $runRoot "failure-$RunNumber.json"
    @{baseCommit=$baseCommit;tests=$baselineTests;evidence=$evidence} | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath $manifest -Encoding utf8
    $digest='sha256:'+(Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash.ToLowerInvariant()
    $failedAttempt=$claim.attempt.attemptId
    Invoke-ControlApi "$attemptPath/results" @{
        schemaVersion='1';projectId=$controlProject;taskId="FIX-$RunNumber";revision=1
        attemptId=$failedAttempt;leaseEpoch=$claim.lease.leaseEpoch;outcome='FAILED'
        resultCommit=$null;resultDigest=$digest;manifestUri=([uri]$manifest).AbsoluteUri;evidence=@($evidence)
    } $attemptActorToken | Out-Null
    $taskPath="/projects/$controlProject/tasks/FIX-$RunNumber/revisions/1"
    $archived=Read-ControlApi "/projects/$controlProject/library/tasks/FIX-$RunNumber/revisions/1/failure-evidence"
    if ($archived.attemptId -cne $failedAttempt -or $archived.resultDigest -cne $digest `
            -or $archived.evidence.Count -ne 1 -or $archived.evidence[0].digest -cne $evidence.digest) {
        throw 'Library failure evidence does not match the reproduced baseline.'
    }
    $state=Read-ControlApi $taskPath
    $queued=Invoke-ControlApi "$taskPath/verification" `
        @{accepted=$false;resultDigest=$digest;routeToBugfix=$true} $adminToken ('"'+$state.snapshot.rowVersion+'"')
    if ($queued.state -cne 'READY') { throw 'Repair was not queued.' }
    $script:claim=Invoke-ControlApi "/projects/$controlProject/bugfix/claims" @{} $bugfixToken
    if ($claim.attempt.workerId -cne 'bugfix' -or $claim.lease.leaseEpoch -ne 2 -or $claim.repair.attemptId -cne $failedAttempt `
            -or $claim.task.taskId -cne "FIX-$RunNumber" -or $claim.task.baseCommit -cne $baseCommit `
            -or $claim.repair.resultDigest -cne $digest) { throw 'Unexpected repair lease or failure evidence.' }
    Start-ControlAttempt $bugfixToken
}
function Complete-ControlTask([int]$RunNumber,[string]$Commit,[string]$Workspace,[string]$AgentReport) {
    $manifest=Join-Path $runRoot "manifest-$RunNumber.json"
    $testReport=Join-Path $Workspace 'target/surefire-reports/TEST-demo.PriceCalculatorTest.xml'
    $evidence=@{artifactId="tests-$RunNumber";uri=([uri]$testReport).AbsoluteUri;digest='sha256:'+(Get-FileHash $testReport -Algorithm SHA256).Hash.ToLowerInvariant()}
    @{baseCommit=$baseCommit;commit=$Commit;tests=$tests;model=(Get-Content $AgentReport -Raw | ConvertFrom-Json);evidence=$evidence} |
        ConvertTo-Json -Depth 15 | Set-Content $manifest -Encoding utf8
    $digest='sha256:'+(Get-FileHash $manifest -Algorithm SHA256).Hash.ToLowerInvariant()
    Invoke-ControlApi "$attemptPath/results" @{
        schemaVersion='1';projectId=$controlProject;taskId="FIX-$RunNumber";revision=1
        attemptId=$claim.attempt.attemptId;leaseEpoch=$claim.lease.leaseEpoch;outcome='PASSED'
        resultCommit=$Commit;resultDigest=$digest;manifestUri=([uri]$manifest).AbsoluteUri;evidence=@($evidence)
    } $attemptActorToken | Out-Null
    $state=Invoke-RestMethod -NoProxy:$controlIsLoopback -MaximumRedirection 0 -Uri "$ControlUrl/api/v1/projects/$controlProject/tasks/FIX-$RunNumber/revisions/1" `
        -Headers @{Authorization="Bearer $adminToken"}
    $verified=Invoke-ControlApi "/projects/$controlProject/tasks/FIX-$RunNumber/revisions/1/verification" `
        @{accepted=$true;resultDigest=$digest} $adminToken ('"'+$state.snapshot.rowVersion+'"')
    if($verified.state -ne 'SUCCEEDED'){throw 'Control service did not accept verified result.'}
    return @{projectId=$controlProject;taskId="FIX-$RunNumber";attemptId=$claim.attempt.attemptId;workerId=$claim.attempt.workerId;
        state=$verified.state;manifestDigest=$digest;repair=$claim.repair}
}
