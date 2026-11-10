# 本机启动与候选发布

从项目根目录用 PowerShell 7 启动 Windows 控制服务。它使用本机 PostgreSQL，数据库与管理员凭据从当前用户的加密凭据文件读取。启动时由 Flyway 应用数据库迁移。

```powershell
./scripts/Start-Platform.ps1
./scripts/Verify-Platform.ps1
```

验收服务地址为 `http://127.0.0.1:18080/actuator/health`。`Verify-Platform.ps1` 运行 Java、PostgreSQL 集成和契约测试。需要另行执行模型样例时使用 `-WithModel`，该选项会联系已配置的 DeepSeek 服务。

## 运行 Java 样例的隔离验收

启动 Docker Linux 引擎后，用仓库中的固定摘要构建文件预热样例依赖。随后按 Git SHA 导出 `pom.xml`、样例源码和固定测试，核对验收输入并离线构建。运行器使用非 root 用户、断网、只读根文件系统、全部 capability 移除和进程/内存限制。运行结束后清理本次容器，JUnit 报告保留在 `target/isolated-tests`。

```powershell
docker build -t jdda-fixture-runner:1 `
    -f platform/agent-poc/fixture-runner.Dockerfile platform/agent-poc/src/main/resources/fixture
./scripts/Test-IsolatedJavaCandidate.ps1 -Repository $candidateRepository -Commit $candidateSha
```

此运行器按固定 Java 样例的 Maven 依赖和六项断言构建。通用业务仓库需要独立、受信任的验收配置。[Docker 容器运行文档](https://docs.docker.com/engine/containers/run/)

两台主机可加载同一份已校验的镜像归档。对较旧内核可用 `prctl(PR_GET_NO_NEW_PRIVS)` 检查是否启用了禁止提权。[Linux 内核接口](https://man7.org/linux/man-pages/man2/PR_GET_NO_NEW_PRIVS.2const.html)

## 双主机 Worker 领取和隔离验收

远端 Worker 使用项目绑定凭据，经固定主机公钥的 SSH 回环转发访问本机控制服务。保持控制服务监听 `127.0.0.1:18080`，从 Windows 建立转发时，远端监听同样绑定 `127.0.0.1`。主机公钥使用已核验的 `known_hosts` 文件。

```powershell
ssh -N -T -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=known_hosts `
    -R 127.0.0.1:18081:127.0.0.1:18080 worker@worker.example.com
```

在 Worker 主机准备 JDK 17 及以上版本、Docker、项目构建产物中的 Jackson `annotations`、`core`、`databind` 三个 JAR，以及 [Worker 源文件](../platform/agent-poc/src/main/java/io/jdda/poc/IsolatedFixtureWorker.java)。将 JAR 放在 `lib/`。`profile.json` 包含 `repoId`、40 位 `baseCommit`、该主机上的完整 `image` 摘要、唯一 `ownedPath` 和 `inputDigests`。`inputDigests` 为三个 fixture 文件相对路径到 `sha256:` 摘要的映射。把从候选提交导出的 `pom.xml`、`src/main/java/demo/PriceCalculator.java` 和 `src/test/java/demo/PriceCalculatorTest.java` 放在同目录下的 `input/`。批准前核对 POM 和测试输入与固定验收配置一致。

注册该 Worker 的项目、ID、主机和 `fixture-offline` 能力，并把该 Worker 的令牌注入 `JDDA_WORKER_TOKEN`。发布的任务须匹配该分支及配置，`toolPolicyId` 为 `fixture-offline-runner`，`acceptanceProfile` 为 `fixture-six-tests`，资源为 2 CPU 和 1024 MiB。远端在包含以上文件的目录执行下列命令，等待领取一项任务并回传结果。本机 Worker 使用 `18080`。

```bash
java -cp 'lib/*' IsolatedFixtureWorker.java http://127.0.0.1:18081 PROJECT WORKER HOST profile.json
```

Worker 使用独立目录和断网容器，测试完成后提交 `VERIFYING` 结果。协调器通过可信文件通道读取 `attempts/` 中的测试报告和清单，核对 SHA256、任务和租约后提交管理员验收。为每台执行主机配置独立的 `JDDA_WORKER_HOST_ID`，从服务端事件检查租约和并发区间。

## 发布已审查候选

先使用 `New-LibraryCandidate.ps1` 从已验收来源生成候选。发布需要该项目的 GitHub 仓库绑定、固定验收工作流及有权写入所选分支的 SSH 身份。`Publish` 在 `jdda/candidates/<SHA>` 发布精确候选。等对应 GitHub Actions 运行完成后，用它的运行 ID 核验并审批。审批固定仓库、目标分支、旧基线、候选 SHA 和 CI 运行次数，有效期 15 分钟。

先从 GitHub Actions 获取工作流 ID，并对已审查基线读取该工作流的 Git blob SHA。用 `Register-GitHubCi.ps1 -ProjectId <项目> -RepoId <仓库标识> -Slug <组织/仓库> -WorkflowId <ID> -WorkflowBlobSha <SHA>` 保存绑定。

```powershell
$common = @{ProjectId=$projectId; CandidateSha=$candidateSha}
./scripts/Release-LibraryCandidate.ps1 -Action Publish @common -CandidateRepository $candidateRepository
./scripts/Release-LibraryCandidate.ps1 -Action Verify @common -RunId $runId
$approval = ./scripts/Release-LibraryCandidate.ps1 -Action Approve @common -RunId $runId
./scripts/Release-LibraryCandidate.ps1 -Action Merge @common `
    -CandidateRepository $candidateRepository -ApprovalId $approval.approvalId
```

审批前服务读取远端差异。固定 Java 样例仍只允许修改任务授权的现有源码。`repository-files-v1` 支持精确授权的服务模块、前端文件、部署文件和旧源码迁移。迁移同时核对原路径和新路径，CI 工作流和验收脚本保留原内容。按顺序合并任务时，每个 Worker 的基线必须已在目标或前一个已接受的来源中。推送前再次核验 CI 与目标旧基线，并登记 `MERGING`。Git 只允许候选对目标基线进行快进，并用预期基线条件保护并发更新。远端分支读回精确候选 SHA 后才登记 `INTEGRATED`，依赖该任务的 `INTEGRATED` 门禁随之放行。GitHub 分支保护规则仍由目标仓库执行。[Git 推送条件](https://git-scm.com/docs/git-push) · [GitHub 分支引用](https://docs.github.com/en/rest/git/refs?apiVersion=2022-11-28)

推送成功但远端读取暂未收敛时，先检查 `Status`。如果远端已指向获批 SHA，可以运行 `Confirm` 完成数据库对账。该操作只核对远端引用并写入本机确认。

```powershell
./scripts/Release-LibraryCandidate.ps1 -Action Status @common
./scripts/Release-LibraryCandidate.ps1 -Action Confirm @common -ApprovalId $approval.approvalId
```

发布后核对候选完整 SHA、GitHub Actions 运行、审批状态和远端引用。[生成项目验收](PROJECT_ACCEPTANCE.md)
