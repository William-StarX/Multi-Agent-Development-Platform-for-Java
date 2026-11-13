# MADPJ

Java 多智能体开发平台。启动本地控制台后，可以按项目清单分派模块和包级任务，让多个 Agent 提交实现，再整合候选并运行容器验收。

A multi-agent development platform for Java. Start the local console, assign module and package tasks from a project manifest, collect agent commits, and verify the integrated candidate in containers.

## 快速开始：打开控制台<br>Quick start: open the console

Windows 本机需要 **PowerShell 7、JDK 21、Maven、Git**，并启动 Docker 的 Linux 容器引擎。首次运行会构建平台，并在需要时初始化 PostgreSQL。

On Windows, install **PowerShell 7, JDK 21, Maven, and Git**, and start Docker with its Linux container engine. The first run builds the platform and initializes PostgreSQL when needed.

```powershell
git clone --branch jdda/bootstrap https://github.com/William-StarX/Multi-Agent-Development-Platform-for-Java.git madpj
cd madpj
./scripts/Start-JddaRoot.ps1
```

脚本会启动或复用控制服务，建立项目 Root 身份，并打开已配对的控制台。首次配置模型时，在终端提示中输入 API Key。再次运行同一命令即可重新进入。

The script starts or reuses the control service, establishes the project's Root role, and opens the paired console. Enter the model API key at the terminal prompt on first setup. Run the same command again to reconnect.

使用 Codex 时，也可以将 `skills/jdda-root` 复制到 `~/.codex/skills/`，然后在项目目录调用 `$jdda-root`。自定义模型地址、远程连接和手动数据库配置见 [部署手册](docs/PLATFORM_SETUP.md)。

With Codex, you can also copy `skills/jdda-root` to `~/.codex/skills/` and invoke `$jdda-root` from the checkout. See the [deployment guide](docs/PLATFORM_SETUP.md) for model endpoints, remote access, and manual database setup.

## 生成一个示例项目<br>Generate an example project

生成项目还需要 **Python 3.12**。先查看预置清单会分派哪些任务，再运行生成。下方命令在平台仓库根目录执行，输出目录使用一个新目录。

Project generation also requires **Python 3.12**. Preview the tasks in the supplied manifest, then generate the project. Run the commands from the platform repository root and choose a new output directory.

```powershell
python -B scripts/Generate-Project.py --manifest examples/projects/reservations.json --plan-only

$manifest = 'examples/projects/reservations.json'
$out = 'target/generated-projects/reservations-demo'
./scripts/Invoke-ProjectGeneration.ps1 -Manifest $manifest -Output $out
```

生成的项目位于 `$out`，候选提交与任务结果记录在 `$out.jdda/generation.json`。定制项目时，修改清单中的模块、路由和任务文件范围，格式见 [项目清单与配置](docs/PROJECT_GENERATION.md)。

The generated project is in `$out`; candidate commits and task results are recorded in `$out.jdda/generation.json`. To customize a project, edit its modules, routes, and task file scopes as described in the [manifest guide](docs/PROJECT_GENERATION.md).

## 验证生成结果<br>Verify the generated project

保持 Docker Compose 可用，对刚生成的候选运行预置验收：

With Docker Compose running, verify the generated candidate using the supplied acceptance suite:

```powershell
python -B scripts/Verify-GeneratedProject.py --manifest $manifest --generation "$out.jdda/generation.json" --suite reservations
```

验收器按候选 SHA 构建镜像、启动隔离环境并运行负载，报告写入 `$out.jdda/verify-*/report.json`。需要在验收通过后推进生成仓库的本地 `main` 时，在命令末尾加入 `--promote`。

The verifier builds images from the candidate SHA, starts an isolated environment, and runs the workload. Reports are written to `$out.jdda/verify-*/report.json`. Add `--promote` to advance the generated repository's local `main` after successful verification.

[已有验收记录](docs/PROJECT_ACCEPTANCE.md) · [自定义验收与部署](docs/PROJECT_GENERATION.md) · [候选发布](docs/RELEASE_RUNBOOK.md)

[Acceptance records](docs/PROJECT_ACCEPTANCE.md) · [Custom verification and deployment](docs/PROJECT_GENERATION.md) · [Candidate release](docs/RELEASE_RUNBOOK.md)

## 本项目提供了什么<br>What the platform provides

- **任务分到具体代码范围。** Root 与各级协调 Agent 按模块、包和文件组织任务，Worker 从共同基线提交各自的实现。

  **Tasks map to concrete code scopes.** Root and coordinating agents organize work by module, package, and file. Workers submit their implementations from a common baseline.

- **整合与修复有记录可查。** Library 收集任务提交并整合候选，Bugfix 可以依据失败证据修复；后续检查对应明确的候选版本。

  **Integration and repair leave a record.** Library collects task commits and integrates candidates. Bugfix can work from failure evidence, and later checks refer to a specific candidate revision.

- **生成后可以直接验收。** 项目清单同时描述模块、路由和基础设施，配套 Docker 验收与 Nacos 运行策略管理，减少手工拼接各模块的工作。

  **Generated projects come with verification support.** Manifests describe modules, routes, and infrastructure, with Docker acceptance checks and Nacos runtime policy management to reduce manual assembly.

## 架构与技术基础<br>Architecture and technical foundations

平台基于 Java、Spring Boot 和 PostgreSQL 构建；生成项目按清单使用 Nacos、Redis、RabbitMQ 等组件。Agent 的组织关系、分工和 Git 整合流程见 [Agent 架构](contracts/AGENT_ARCHITECTURE.md)，运行策略见 [项目维护手册](docs/PROJECT_GENERATION.md)。

The platform uses Java, Spring Boot, and PostgreSQL. Generated projects use components such as Nacos, Redis, and RabbitMQ according to their manifests. See [agent architecture](contracts/AGENT_ARCHITECTURE.md) for roles and Git integration, and the [project maintenance guide](docs/PROJECT_GENERATION.md) for runtime policies.

## 注意事项<br>Attention

首次构建需要下载依赖与容器镜像，项目生成会调用已配置的模型服务。API Key 按终端提示输入并保存在本机配置中。

The first build downloads dependencies and container images; project generation calls the configured model service. Enter API keys through the terminal prompt and keep them in local configuration.
