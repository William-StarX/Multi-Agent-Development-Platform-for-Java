param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')][string]$ProjectId,
    [Parameter(Mandatory)][string]$TargetRepository,
    [Parameter(Mandatory)][ValidatePattern('^refs/heads/[A-Za-z0-9][A-Za-z0-9._/-]{0,120}$')][string]$TargetRef,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedBaseSha,
    [Parameter(Mandatory)][ValidateCount(1,32)][hashtable[]]$Sources,
    [string]$ControlUrl
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')
$ControlUrl = Resolve-JddaControlUrl $ControlUrl
$controlIsLoopback = ([uri]$ControlUrl).IsLoopback
$projectRoot = Split-Path -Parent $PSScriptRoot
$target = (Resolve-Path -LiteralPath $TargetRepository).ProviderPath
$runPath = Join-Path $projectRoot ('target/integrations/' + [Guid]::NewGuid().ToString('N'))
$gitPath = Join-Path $runPath 'candidate.git'
$shaPattern = '^[0-9a-f]{40}$'
$headers = @{
    Authorization = 'Bearer ' + (Get-JddaAdminToken -ControlUrl $ControlUrl)
}
$api = "$ControlUrl/api/v1/projects/$ProjectId/library"

function Invoke-IntegrationGit([string]$Repository, [string[]]$GitArgs) {
    $output = & git -C $Repository -c core.hooksPath=/dev/null @GitArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Git $($GitArgs[0]) failed: $($output -join "`n")" }
    return $output
}

function Get-ScopedChanges([string]$Base, [string]$Commit, [string[]]$AllowedPaths, [string[]]$ModulePaths,
        [string[]]$GeneratedPaths) {
    $changes = @(Invoke-IntegrationGit $gitPath @('diff-tree','--no-commit-id','-r','--raw','--no-renames',$Base,$Commit))
    foreach ($line in $changes) {
        if ($line -cnotmatch '^:(000000|100644) (000000|100644) [0-9a-f]+ [0-9a-f]+ ([AMD])\t([A-Za-z0-9_.$/-]+)$') {
            throw 'Integration policy requires a normal source file change.'
        }
        $modes = $Matches[1] + ' ' + $Matches[2]
        $change = $Matches[3]
        $path = $Matches[4]
        $javaEdit = $path -cin $AllowedPaths -and $change -ceq 'M' -and $modes -ceq '100644 100644' `
            -and $path -cmatch '^src/main/java/(?:[A-Za-z0-9_$]+/)*[A-Za-z0-9_$]+\.java$'
        $moduleEdit = $path -cin $ModulePaths -and (
            ($change -ceq 'D' -and $modes -ceq '100644 000000' -and $path -cmatch '^(?:(?:[a-z]+-service/)?src/main/java/(?:[A-Za-z0-9_]+/)*[A-Za-z0-9_]+\.java|src/main/resources/(?:application\.yml|db/migration/[A-Za-z0-9_.-]+\.sql))$') -or
            ((($change -ceq 'A' -and $modes -ceq '000000 100644') -or ($change -ceq 'M' -and $modes -ceq '100644 100644')) -and
                ($path -cmatch '^(?:[a-z]+-service/(?:pom\.xml|src/main/(?:java/(?:[A-Za-z0-9_]+/)*[A-Za-z0-9_]+\.java|resources/(?:application\.yml|db/migration/[A-Za-z0-9_.-]+\.sql)))|web-ui/(?:index\.html|styles\.css|app\.js|Dockerfile|nginx\.conf))$' -or
                 ($change -ceq 'M' -and $path -cin @('pom.xml','Dockerfile','compose.yaml','README.md')))))
        $generatedAdd = $path -cin $GeneratedPaths -and $change -ceq 'A' -and $modes -ceq '000000 100644' `
            -and $path -cnotmatch '/runtime/' `
            -and $path -cmatch '^[a-z][a-z0-9]*(?:-[a-z0-9]+)*-service/src/main/java/(?:[A-Za-z0-9_]+/)+[A-Za-z][A-Za-z0-9_]*\.java$'
        if (-not ($javaEdit -or $moduleEdit -or $generatedAdd)) { throw "Change is outside the task's exact source policy: $path" }
        $path
    }
}

Invoke-IntegrationGit $target @('check-ref-format',$TargetRef) | Out-Null
$base = Invoke-IntegrationGit $target @('rev-parse','--verify',($TargetRef+'^{commit}'))
if ($base -cne $ExpectedBaseSha) { throw 'Target base changed. Review the new head before rebuilding.' }

$materials = @()
foreach ($source in $Sources) {
    if ($source.taskId -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' -or [long]$source.revision -lt 1) {
        throw 'A source must specify a task ID, positive revision and local repository.'
    }
    $material = Invoke-RestMethod -NoProxy:$controlIsLoopback -MaximumRedirection 0 "$api/tasks/$($source.taskId)/revisions/$([long]$source.revision)/accepted-result" `
        -Headers $headers -TimeoutSec 10
    if ($material.baseCommit -cnotmatch $shaPattern -or $material.resultCommit -cnotmatch $shaPattern) {
        throw 'The accepted result must name full Git SHA-1 commits.'
    }
    if ($materials.material.taskId -ccontains $material.taskId) { throw 'Each task can appear only once.' }
    if ($materials.Count -gt 0 -and $material.repoId -cne $materials[0].material.repoId) {
        throw 'All accepted results must belong to the same repository.'
    }
    $materials += @{material=$material; repository=(Resolve-Path -LiteralPath $source.repository).ProviderPath}
}

New-Item -ItemType Directory -Path $gitPath -Force | Out-Null
Invoke-IntegrationGit $gitPath @('init','--bare','--template=') | Out-Null
Invoke-IntegrationGit $gitPath @('fetch','--no-tags','--no-recurse-submodules','--',$target,($TargetRef+':refs/jdda/base')) | Out-Null
if ((Invoke-IntegrationGit $gitPath @('rev-parse','refs/jdda/base')) -cne $base) {
    throw 'Target moved while fetching the base.'
}

$current = $base
$orderedSources = @()
$allowedPaths = @()
$modulePaths = @()
$generatedPaths = @()
foreach ($item in $materials) {
    $material = $item.material
    $commit = $material.resultCommit
    Invoke-IntegrationGit $gitPath @('fetch','--no-tags','--no-recurse-submodules','--',$item.repository,$commit) | Out-Null
    if ((Invoke-IntegrationGit $gitPath @('rev-list','--parents','-n','1',$commit)) -cne "$commit $($material.baseCommit)") {
        throw 'Worker commit must have the accepted task base as its single parent.'
    }
    & git -C $gitPath merge-base --is-ancestor $material.baseCommit $current
    if ($LASTEXITCODE -ne 0) { throw 'Worker base must be present in the target or an earlier accepted source.' }
    $paths = switch -CaseSensitive ($material.toolPolicyId) {
        'repository-files-v1' { @(Get-ScopedChanges $material.baseCommit $commit @() $material.ownedPaths @()) }
        'generated-java-v1' { @(Get-ScopedChanges $material.baseCommit $commit @() @() $material.ownedPaths) }
        default { @(Get-ScopedChanges $material.baseCommit $commit $material.ownedPaths @() @()) }
    }
    if ($paths.Count -eq 0) { throw 'Accepted Worker commit has no source changes.' }
    switch -CaseSensitive ($material.toolPolicyId) {
        'repository-files-v1' { $modulePaths += $paths }
        'generated-java-v1' { $generatedPaths += $paths }
        default { $allowedPaths += $paths }
    }
    $merge = & git -C $gitPath -c core.hooksPath=/dev/null merge-tree --write-tree "--merge-base=$($material.baseCommit)" $current $commit 2>&1
    if ($LASTEXITCODE -ne 0) {
        $conflictLog = Join-Path $runPath 'merge-failure.txt'
        $merge | Set-Content -LiteralPath $conflictLog -Encoding utf8
        throw "Git could not integrate $($material.taskId). Evidence: $conflictLog"
    }
    $tree = [string](@($merge)[0])
    if ($tree -cnotmatch $shaPattern) { throw 'Git did not return a merged tree SHA.' }
    $message = "Library candidate for $TargetRef`n`nTask: $($material.taskId) revision $($material.revision)`nResult: $commit`nDigest: $($material.resultDigest)"
    $current = Invoke-IntegrationGit $gitPath @('-c','user.name=JDDA Library','-c','user.email=library@jdda.invalid',
        'commit-tree',$tree,'-p',$current,'-p',$commit,'-m',$message)
    $orderedSources += @{taskId=$material.taskId;revision=$material.revision;resultCommit=$commit;resultDigest=$material.resultDigest}
}

$changedPaths = @(Get-ScopedChanges $base $current $allowedPaths $modulePaths $generatedPaths)
if ($changedPaths.Count -eq 0) { throw 'The combined candidate has no changes against the target.' }
if ((Invoke-IntegrationGit $target @('rev-parse','--verify',($TargetRef+'^{commit}'))) -cne $base) {
    throw 'Target moved during integration. Rebuild from its new head.'
}
Invoke-IntegrationGit $gitPath @('update-ref','refs/heads/candidate',$current,('0' * 40)) | Out-Null
$body = @{repoId=$materials[0].material.repoId;targetRef=$TargetRef;baseSha=$base;candidateSha=$current;sources=$orderedSources}
$manifest = Join-Path $runPath 'candidate.json'
$body | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifest -Encoding utf8
$headers['Idempotency-Key'] = 'candidate-' + $current
Invoke-RestMethod -NoProxy:$controlIsLoopback -MaximumRedirection 0 -Method Post -Uri "$api/integration-candidates" -Headers $headers -ContentType application/json `
    -Body ($body | ConvertTo-Json -Depth 12) -TimeoutSec 15 | Out-Null
$record = Invoke-RestMethod -NoProxy:$controlIsLoopback -MaximumRedirection 0 "$api/integration-candidates/$current" -Headers $headers -TimeoutSec 10
[pscustomobject]@{Record=$record;Repository=$gitPath;Manifest=$manifest;ChangedPaths=$changedPaths}
