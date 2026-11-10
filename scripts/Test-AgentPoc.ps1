param([ValidateRange(1,3)][int]$Runs = 3, [switch]$SkipBuild, [string]$ControlUrl = '', [switch]$Plan,
    [switch]$Bugfix, [switch]$FixtureModel)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')
. (Join-Path $PSScriptRoot 'ModelProcess.ps1')
if ($ControlUrl -or $Plan -or $Bugfix) {
    $ControlUrl = Resolve-JddaControlUrl $ControlUrl
    $controlIsLoopback = ([uri]$ControlUrl).IsLoopback
}
if ($FixtureModel -and (-not $Bugfix -or $Plan)) { throw 'Offline -FixtureModel requires -Bugfix and cannot run -Plan.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$module = Join-Path $projectRoot 'platform/agent-poc'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw 'JAVA_HOME must point to JDK 21.' }
if (-not $SkipBuild) {
    & mvn -B -ntp -f (Join-Path $module 'pom.xml') package
    if ($LASTEXITCODE -ne 0) { throw 'Agent module build failed.' }
}
$runRoot = Join-Path $module ('target/runs/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
$baseline = Join-Path $runRoot 'baseline'
New-Item -ItemType Directory -Path $baseline -Force | Out-Null
Copy-Item -Path (Join-Path $module 'src/main/resources/fixture/*') -Destination $baseline -Recurse -Force
# Copy dotfiles explicitly as well for consistent Windows PowerShell behavior.
Copy-Item -LiteralPath (Join-Path $module 'src/main/resources/fixture/.gitignore') -Destination $baseline -Force

function Invoke-FixtureGit([string]$Directory, [string[]]$GitArgs) {
    $result = & git -C $Directory @GitArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Git failed: $($GitArgs[0])" }
    return ($result -join "`n").Trim()
}
function Set-FixtureIdentity([string]$Directory) {
    Invoke-FixtureGit $Directory @('config','user.name','JDDA Fixture Agent') | Out-Null
    Invoke-FixtureGit $Directory @('config','user.email','fixture@jdda.invalid') | Out-Null
    Invoke-FixtureGit $Directory @('config','commit.gpgSign','false') | Out-Null
    Invoke-FixtureGit $Directory @('config','core.autocrlf','false') | Out-Null
    Invoke-FixtureGit $Directory @('config','core.hooksPath','.git/disabled-hooks') | Out-Null
}
function Read-TestEvidence([string]$Directory) {
    $reportPath = Join-Path $Directory 'target/surefire-reports/TEST-demo.PriceCalculatorTest.xml'
    if (-not (Test-Path -LiteralPath $reportPath)) { throw 'Maven did not produce the expected test report.' }
    [xml]$report = Get-Content -LiteralPath $reportPath -Raw
    return @{tests=[int]$report.testsuite.tests; failures=[int]$report.testsuite.failures;
        errors=[int]$report.testsuite.errors; skipped=[int]$report.testsuite.skipped}
}

Invoke-FixtureGit $baseline @('init','--initial-branch=main') | Out-Null
Set-FixtureIdentity $baseline
Invoke-FixtureGit $baseline @('add','--','.gitignore','pom.xml','src') | Out-Null
Invoke-FixtureGit $baseline @('commit','-m','Fixture: reproduce discount calculation bug') | Out-Null
$baseCommit = Invoke-FixtureGit $baseline @('rev-parse','HEAD')
& mvn -B -ntp -f (Join-Path $baseline 'pom.xml') test *> (Join-Path $runRoot 'baseline-test.log')
$baselineExit = $LASTEXITCODE
$baselineTests = Read-TestEvidence $baseline
if ($baselineExit -eq 0 -or $baselineTests.tests -ne 6 -or $baselineTests.failures -ne 4 -or $baselineTests.errors -ne 0) {
    throw 'Baseline must fail exactly four assertions in six tests; inspect baseline-test.log.'
}

$classPath = (Join-Path $module 'target/classes') + ';' + (Join-Path $module 'target/dependency/*')
if ($FixtureModel) {
    $classPath += ';' + (Join-Path $module 'target/test-classes')
}

function Invoke-PocAgent([string[]]$AgentArgs, [switch]$Heartbeat) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new($java)
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    foreach ($argument in (@('-cp',$classPath) + $AgentArgs)) { $startInfo.ArgumentList.Add($argument) }
    Set-JddaModelProcessEnvironment $startInfo -Offline:$FixtureModel
    $agentProcess = [Diagnostics.Process]::Start($startInfo)
    $deadline = [DateTime]::UtcNow.AddMinutes(4)
    try {
        while (-not $agentProcess.WaitForExit(10000)) {
            if ([DateTime]::UtcNow -gt $deadline) { throw 'Agent exceeded four minutes.' }
            if ($Heartbeat) { Send-ControlHeartbeat }
        }
        if ($agentProcess.ExitCode -ne 0) { throw "Agent failed: $($AgentArgs[0])" }
    } finally {
        if (-not $agentProcess.HasExited) { $agentProcess.Kill($true) }
        $agentProcess.Dispose()
    }
}

$results = @()
$controlWorker = $null
if ($ControlUrl) {
    . (Join-Path $PSScriptRoot 'Control-Poc.ps1')
    Initialize-ControlPoc
}
for ($run = 1; $run -le $Runs; $run++) {
    $workspace = Join-Path $runRoot "run-$run"
    Invoke-FixtureGit $runRoot @('clone','-c','core.autocrlf=false','--no-hardlinks','--',$baseline,$workspace) | Out-Null
    Set-FixtureIdentity $workspace
    if ((Invoke-FixtureGit $workspace @('rev-parse','HEAD')) -ne $baseCommit) { throw 'Baseline SHA mismatch.' }
    if (Invoke-FixtureGit $workspace @('status','--porcelain')) { throw 'Clone is not clean before model invocation.' }
    $planning = $null
    if ($Plan) {
        $topologyPath = Join-Path $runRoot "topology-$run.json"
        $planningPath = Join-Path $runRoot "planning-$run.json"
        Get-ControlTopology | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $topologyPath -Encoding utf8
        Invoke-PocAgent @('io.jdda.poc.DelegationPlanner',$topologyPath,$planningPath)
        $planning = Get-Content -LiteralPath $planningPath -Raw | ConvertFrom-Json
    }
    if ($ControlUrl) { Start-ControlTask $run $baseCommit $planning }
    $agentReport = Join-Path $runRoot "agent-$run.json"
    $workerArgs = @('io.jdda.poc.AgentPoc',$workspace,$agentReport)
    if ($Bugfix) {
        Move-ControlTaskToBugfix $run
        $repairClaimPath = Join-Path $runRoot "repair-claim-$run.json"
        $claim | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $repairClaimPath -Encoding utf8
        if ($FixtureModel) { $workerArgs[0] = 'io.jdda.poc.BugfixFixtureModel' }
        $workerArgs += @($repairClaimPath,'--bugfix')
    } elseif ($Plan) {
        $claimedTaskPath = Join-Path $runRoot "claimed-task-$run.json"
        $claim.task | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $claimedTaskPath -Encoding utf8
        $workerArgs += $claimedTaskPath
    }
    Invoke-PocAgent $workerArgs -Heartbeat:([bool]$ControlUrl)
    $changed = Invoke-FixtureGit $workspace @('diff','--name-only')
    if ($changed -ne 'src/main/java/demo/PriceCalculator.java') { throw 'Unexpected changed files.' }
    if (Invoke-FixtureGit $workspace @('ls-files','--others','--exclude-standard')) { throw 'Unexpected untracked files.' }
    & mvn -B -ntp -f (Join-Path $workspace 'pom.xml') test *> (Join-Path $runRoot "test-$run.log")
    if ($LASTEXITCODE -ne 0) { throw "Tests failed in run $run; no commit created." }
    $tests = Read-TestEvidence $workspace
    if ($tests.tests -ne 6 -or $tests.failures -ne 0 -or $tests.errors -ne 0 -or $tests.skipped -ne 0) {
        throw 'Fixture test gate failed.'
    }
    Invoke-FixtureGit $workspace @('diff','--check') | Out-Null
    Invoke-FixtureGit $workspace @('add','--','src/main/java/demo/PriceCalculator.java') | Out-Null
    Invoke-FixtureGit $workspace @('commit','-m','Fix: apply discount and floor payable at zero') | Out-Null
    $commit = Invoke-FixtureGit $workspace @('rev-parse','HEAD')
    if (Invoke-FixtureGit $workspace @('status','--porcelain')) { throw 'Worktree is not clean after commit.' }
    $modelEvidence = Get-Content -LiteralPath $agentReport -Raw | ConvertFrom-Json
    $controlEvidence=$null
    if ($ControlUrl) { $controlEvidence=Complete-ControlTask $run $commit $workspace $agentReport }
    $candidate=$null
    if ($Bugfix) {
        $candidate = & (Join-Path $PSScriptRoot 'New-LibraryCandidate.ps1') -ProjectId $controlProject `
            -TargetRepository $baseline -TargetRef 'refs/heads/main' -ExpectedBaseSha $baseCommit `
            -Sources @(@{taskId="FIX-$run";revision=1;repository=$workspace})
        if ($candidate.Record.sources[0].resultCommit -cne $commit) { throw 'Library candidate differs from the verified repair.' }
    }
    $results += @{run=$run; baseCommit=$baseCommit; commit=$commit; workspace=$workspace;
        tests=$tests; model=$modelEvidence; planning=$planning; control=$controlEvidence; libraryCandidate=$candidate; status='PASSED'}
    @{status='IN_PROGRESS'; baselineTests=$baselineTests; runs=$results} | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath (Join-Path $runRoot 'report.json') -Encoding utf8
    Write-Host "Run $run passed: six tests, commit $commit"
}
@{status='PASSED'; baselineTests=$baselineTests; runs=$results} | ConvertTo-Json -Depth 12 |
    Set-Content -LiteralPath (Join-Path $runRoot 'report.json') -Encoding utf8
Write-Host "Evidence: $runRoot/report.json"
if ($ControlUrl) {
    Invoke-ControlApi "/projects/$controlProject/agents/$controlWorker/credentials/revoke" @{} $adminToken | Out-Null
    if ($Bugfix) { Invoke-ControlApi "/projects/$controlProject/agents/bugfix/credentials/revoke" @{} $adminToken | Out-Null }
}
