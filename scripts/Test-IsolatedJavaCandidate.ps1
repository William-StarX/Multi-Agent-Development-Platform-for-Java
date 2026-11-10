param(
    [Parameter(Mandatory)][string]$Repository,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$Commit
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$source = (Resolve-Path -LiteralPath $Repository).ProviderPath
$run = Join-Path $projectRoot ('target/isolated-tests/' + [Guid]::NewGuid().ToString('N'))
$archive = Join-Path $run 'source.zip'
$inputPath = Join-Path $run 'input'
$outputPath = Join-Path $run 'reports'
New-Item -ItemType Directory -Path $run,$outputPath -Force | Out-Null
$fixtureFiles = @('pom.xml','src/main/java/demo/PriceCalculator.java','src/test/java/demo/PriceCalculatorTest.java')
& git -C $source archive --format=zip "--output=$archive" $Commit -- @fixtureFiles
if ($LASTEXITCODE -ne 0) { throw 'Could not export the exact Git commit.' }
Expand-Archive -LiteralPath $archive -DestinationPath $inputPath

# The warmed image and acceptance assertions belong to this specific Java fixture.
$fixture = Join-Path $projectRoot 'platform/agent-poc/src/main/resources/fixture'
foreach ($relativePath in @('pom.xml','src/test/java/demo/PriceCalculatorTest.java')) {
    $expected = (Get-Content -LiteralPath (Join-Path $fixture $relativePath) -Raw).Replace("`r`n","`n")
    $actual = (Get-Content -LiteralPath (Join-Path $inputPath $relativePath) -Raw).Replace("`r`n","`n")
    if ($expected -cne $actual) { throw "Candidate changed the fixture acceptance input: $relativePath" }
}

function Invoke-RunnerDocker([string[]]$DockerArgs) {
    $output = & docker @DockerArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Docker $($DockerArgs[0]) failed: $($output -join [Environment]::NewLine)" }
    $output
}
$image = Invoke-RunnerDocker @('image','inspect','--format','{{.Id}}','jdda-fixture-runner:1')
if ($image -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'Build jdda-fixture-runner:1 before running the fixture.' }
$name = 'jdda-fixture-' + [Guid]::NewGuid().ToString('N')
$command = @'
test "$(id -u)" -eq 65532
test ! -S /var/run/docker.sock
if grep -q '^NoNewPrivs:' /proc/self/status; then
    grep -Eq '^NoNewPrivs:[[:space:]]+1$' /proc/self/status
else
    # Linux before 4.10 lacks the proc field. On x86_64, read PR_GET_NO_NEW_PRIVS via prctl.
    test "$(uname -m)" = x86_64
    perl -e 'exit(syscall(157, 39, 0, 0, 0, 0) == 1 ? 0 : 1)'
fi
test "$(wc -l < /proc/net/route)" -eq 1
cp -R /input/. /work
cp -R /opt/jdda-m2 /work/repository
chmod -R u+rwX /work/repository
cd /work
mvn -B -ntp -o -Duser.home=/tmp -Dmaven.repo.local=/work/repository test
cp target/surefire-reports/TEST-demo.PriceCalculatorTest.xml /reports/
'@
$container = Invoke-RunnerDocker @('create','--name',$name,'--label','io.jdda.fixture-test=true','--pull=never',
    '--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',
    '--user','65532:65532','--cpus','2','--memory','1g','--memory-swap','1g','--pids-limit','128',
    '--tmpfs','/tmp:rw,nosuid,nodev,size=128m,mode=1777','--tmpfs','/work:rw,nosuid,nodev,size=512m,mode=1777',
    '--mount',"type=bind,source=$inputPath,target=/input,readonly",'--mount',"type=bind,source=$outputPath,target=/reports",
    '--env','HOME=/tmp','--entrypoint','/bin/sh',$image,'-ec',$command.Replace("`r`n","`n"))
try {
    Invoke-RunnerDocker @('start',$container) | Out-Null
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        $state = Invoke-RunnerDocker @('inspect','--format','{{json .State}}',$container) | ConvertFrom-Json
        if (-not $state.Running) { break }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Isolated fixture exceeded the time limit.' }
        Start-Sleep -Seconds 1
    } while ($true)
    $logs = Join-Path $run 'container.log'
    Invoke-RunnerDocker @('logs',$container) | Set-Content -LiteralPath $logs -Encoding utf8
    if ($state.ExitCode -ne 0) { throw "Isolated fixture failed. Log: $logs" }
    $report = Join-Path $outputPath 'TEST-demo.PriceCalculatorTest.xml'
    [xml]$xml = Get-Content -LiteralPath $report -Raw
    $tests = $xml.testsuite
    if ([int]$tests.tests -ne 6 -or [int]$tests.failures -ne 0 -or [int]$tests.errors -ne 0 -or [int]$tests.skipped -ne 0) {
        throw 'Isolated fixture did not pass all six assertions.'
    }
    $config = Invoke-RunnerDocker @('inspect','--format','{{json .HostConfig}}',$container) | ConvertFrom-Json
    [pscustomobject]@{commit=$Commit;image=$image;tests=[int]$tests.tests;report=$report;log=$logs;
        reportSha256=(Get-FileHash -LiteralPath $report -Algorithm SHA256).Hash.ToLowerInvariant();
        network=$config.NetworkMode;readOnly=$config.ReadonlyRootfs;memoryBytes=$config.Memory;pidsLimit=$config.PidsLimit}
} finally {
    Invoke-RunnerDocker @('rm','-f',$container) | Out-Null
}
