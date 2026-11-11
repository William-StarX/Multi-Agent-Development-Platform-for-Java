# JDDA

[中文](#中文) · [English](#english)

## 中文

JDDA 按模块和包分派 Java 开发任务。控制服务用 PostgreSQL 保存任务与租约，生成的服务用 Nacos 管理运行策略。

### 架构

```mermaid
flowchart TB
    control["控制台 · 控制服务 · PostgreSQL"] <-->|"任务与租约"| agents
    subgraph agents["项目 Agent"]
        direction TB
        root["Root 总控"] --> middle["项目中级"] --> scope["模块次中级"] --> worker["包级 Worker"]
        root --> specialists
        subgraph specialists["Root 直属"]
            direction LR
            library["Library · 任务索引与 Git 整合"] <-->|"证据与修复提交"| bugfix["Bugfix · 专职修复"]
        end
    end
```

Worker 从同一 Git 基线独立提交。Library 整合候选，验收器按候选 SHA 构建 Docker 镜像并运行负载。[Agent 架构](contracts/AGENT_ARCHITECTURE.md)

### 启动

在 Windows 上准备 PowerShell 7 和 JDK 21，从源码构建时使用 Maven。将 `skills/jdda-root` 复制到 Codex 的 `~/.codex/skills/` 目录，再在项目目录调用 `$jdda-root`。也可直接运行 `./scripts/Start-JddaRoot.ps1`。

入口启动或复用控制服务，建立项目 Root 身份并打开已配对的控制台。首次配置模型时，在本机终端输入 API Key。验证通过后会加密保存。首次自动初始化数据库使用 Docker 的 Linux 容器引擎。[Skill](skills/jdda-root/SKILL.md) · [部署手册](docs/PLATFORM_SETUP.md)

### 生成与验收

项目生成需要 Git 和 Python 3.12，容器验收需要 Docker Compose。`--promote` 在验收通过且基线匹配时快进本地 `main`。

```powershell
$manifest = 'examples/projects/reservations.json'
$out = 'target/generated-projects/reservations-demo'
./scripts/Invoke-ProjectGeneration.ps1 -Manifest $manifest -Output $out
python -B scripts/Verify-GeneratedProject.py --manifest $manifest --generation "$out.jdda/generation.json" --suite reservations --promote
```

[项目清单与配置](docs/PROJECT_GENERATION.md) · [验收记录](docs/PROJECT_ACCEPTANCE.md)

## English

JDDA delegates Java development by module and package. The control service stores tasks and leases in PostgreSQL. Generated services read their runtime policies from Nacos.

### Architecture

```mermaid
flowchart TB
    control["Console · Control service · PostgreSQL"] <-->|"Tasks and leases"| agents
    subgraph agents["Project agents"]
        direction TB
        root["Root coordinator"] --> middle["Project manager"] --> scope["Module manager"] --> worker["Package worker"]
        root --> specialists
        subgraph specialists["Report to Root"]
            direction LR
            library["Library · Task index and Git integration"] <-->|"Evidence and fix commits"| bugfix["Bugfix agent"]
        end
    end
```

Workers commit independently from a common Git baseline. Library integrates the candidate, and the verifier builds its exact SHA in Docker and runs the workload. [Agent architecture](contracts/AGENT_ARCHITECTURE.md)

### Start JDDA

Use PowerShell 7 and JDK 21 on Windows. A source build uses Maven. Copy `skills/jdda-root` into Codex's `~/.codex/skills/` directory and invoke `$jdda-root` from the checkout, or run `./scripts/Start-JddaRoot.ps1` directly.

The launcher starts or reuses the control service, establishes the project Root role, and opens the paired console. Enter your API key in the local terminal on first setup. JDDA verifies and encrypts it. Automatic database setup uses Docker's Linux container engine. [Skill](skills/jdda-root/SKILL.md) · [Deployment guide](docs/PLATFORM_SETUP.md)

### Generate and verify

Generation requires Git and Python 3.12. Container verification uses Docker Compose. With `--promote`, a passing candidate fast-forwards local `main` when the baseline still matches.

```powershell
$manifest = 'examples/projects/reservations.json'
$out = 'target/generated-projects/reservations-demo'
./scripts/Invoke-ProjectGeneration.ps1 -Manifest $manifest -Output $out
python -B scripts/Verify-GeneratedProject.py --manifest $manifest --generation "$out.jdda/generation.json" --suite reservations --promote
```

[Project manifest and configuration](docs/PROJECT_GENERATION.md) · [Acceptance results](docs/PROJECT_ACCEPTANCE.md)
