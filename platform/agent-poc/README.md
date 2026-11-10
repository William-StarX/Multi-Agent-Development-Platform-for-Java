# Spring AI Alibaba 代码修复 POC

独立模块，直接复用 ReactAgent 的模型、工具、观察循环。依赖 `spring-ai-alibaba-agent-framework:1.1.2.2`、同版 Graph Core 和 Spring AI BOM/OpenAI 适配器 `1.1.2`；使用 JDK 21、Maven 和 Git。默认模型 deepseek-flash，通过 DeepSeek 官方接口调用，关闭思考。

在项目根目录使用 PowerShell 7 执行 `./scripts/Test-AgentPoc.ps1`。默认从同一基线运行 3 次，也可使用 `-Runs 1`。构建及工具测试通过后，脚本创建独立样例仓库，先验证 6 个测试中 4 个断言失败，再调用 Agent 修复。受保护的 6 个测试全部通过、修改范围检查通过后，由脚本生成本地 Git 提交，不推送远端。

每次最多 4 次模型调用、单次最多 512 输出 Token，不自动重试；连接超时 10 秒、读取超时 45 秒，Agent 进程时限 4 分钟。密钥从进程/用户环境变量或 Windows 加密凭据读取，只注入 Agent 子进程。

Agent 只有读取固定样例和替换一个 return 表达式两种工具。表达式允许两个变量、整数、算术、Math.max/min，最多修改两次。Agent 不能改测试、选择任意文件或运行 Shell。表达式编译执行在本机，本模块不是通用不可信代码沙箱。

输出位于 `target/runs/<时间-随机标识>/`：report.json 包含各次测试、模型用量和完整基线/结果 SHA；agent-N.json 是模型阶段记录；baseline-test.log、test-N.log 是测试日志。模型阶段的 PENDING_TRUSTED_RUNNER 表示其自身不宣称测试成功，最终结论以 report.json 为准。

上游：[SAA 发行版](https://github.com/alibaba/spring-ai-alibaba/releases/tag/v1.1.2.2)、[ReactAgent](https://github.com/alibaba/spring-ai-alibaba/blob/v1.1.2.2/spring-ai-alibaba-agent-framework/src/main/java/com/alibaba/cloud/ai/graph/agent/ReactAgent.java)、[Spring AI](https://github.com/spring-projects/spring-ai/tree/v1.1.2)。通过公共 API 接入，没有复制或修改上游源码。核心框架许可证 Apache-2.0；分发前仍需完整传递依赖许可审查。

修复样例已接入控制中心的领取、租约、Worker 身份和结果验收。在项目根目录运行 `./scripts/Verify-Platform.ps1` 可执行本地回归，包括模拟模型规划和真实 PostgreSQL 调度测试。

启动本机服务后，`./scripts/Test-AgentPoc.ps1 -SkipBuild -Runs 1 -Plan -ControlUrl http://127.0.0.1:18080` 会用 DeepSeek 运行一条固定 Java 样例。规划使用开源 Spring AI Alibaba `StateGraph` 串联三个 `ReactAgent`，每层从具备可用 Worker 的直属下属中选择一名接收者。控制服务核对三跳及任务权限。任务包记录原始目标和交接指令，指定 Worker 使用最后一跳指令执行。候选角色标识和指令来自临时项目。模型还会读取固定的 `PriceCalculator.java` 样例。每次运行最多三次规划请求和四次执行请求。[Spring AI Alibaba 工作流](https://www.java2ai.com/docs/frameworks/agent-framework/advanced/workflow/)

运行报告增加 `planning` 和指定 `workerId`，可对照三次交接与实际领取者。此样例覆盖单条 Java 任务链。多任务规划使用下文的批次入口。

固定样例验收会核对三条委派、指定 Worker、执行状态和 `PriceCalculator.java` 的独立提交。[生成项目验收](../../docs/PROJECT_ACCEPTANCE.md)

## Agent 专精与参数微调

| 角色 | 第一项训练目标 | 独立验收 |
| --- | --- | --- |
| Root 总控 | 根据目标、阻塞和预算选择下一步调度 | 优先级正确，保留关键验收条件，无越级委派 |
| 中级 | 划分工作范围，协调模块或资源间的依赖 | 范围归属和依赖方向正确，跨范围冲突上报 |
| 次中级 | 将已有明确范围的任务交给对应执行者 | 只选有资格的直属 Agent，保留资源和验收条件 |
| 低级 Worker | 完成授权范围内的实现和自检 | 独立测试通过，变更限于授权资源 |
| Library | 按需检索任务证据并生成 Git 集成候选 | 摘要可回查，候选与 CI 使用同一完整 SHA |
| Bugfix | 复现故障，定位根因并提出带回归证据的修复 | 原故障消失，回归通过，经 Root 与 Library 交接 |

总控、中级、次中级、Worker 和 Bugfix 各有独立提示。默认模型由 `JDDA_MODEL` 配置，DeepSeek 兼容配置继续支持 `DEEPSEEK_MODEL`。通过 `JDDA_<ROLE>_MODEL` 覆盖模型 ID，通过同一角色的 `_MODEL_BASE_URL` 和 `_MODEL_API_KEY` 指定独立的 Chat Completions 服务。规划报告的 `stageModels` 记录每层实际选用的模型。Library 通过确定性的检索和 Git 集成运行。权限、直属关系和验收由控制服务校验。[部署与模型设置](../../docs/PLATFORM_SETUP.md)

次中级路由先做本地 LoRA 试验。`scope-lora-cases.json` 包含 27 条训练样例和 18 条独立评估样例，覆盖 Java 包、数据库资源及 Nacos 工作范围。训练冻结基础权重，只更新注意力层的 LoRA 参数。训练前后使用同一组提示和相同的贪心解码，分别统计 JSON 格式、接收者及关键字面量保留。字面量检查需要搭配人工复核，合成样例的结果仅用于验证训练流程。候选权重独立存放，须通过真实项目留出集后再接入 Agent。[Qwen 模型](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct) · [PEFT LoRA](https://huggingface.co/docs/peft/en/conceptual_guides/lora)

在项目根目录使用带 CUDA 的 Python 环境运行。环境依赖为 PyTorch `2.8.0+cu126`、Transformers `4.57.6` 和 PEFT `0.18.1`。`base` 目录需要 [固定版本的 Qwen Safetensors 权重、分词器和配置](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct/tree/7ae557604adf67be50417f59c2c2f167def9a775)，可将版本 SHA 写入 `base/revision.txt` 以记录来源。

```powershell
python platform/agent-poc/train_scope_lora.py target/scope-lora/base target/scope-lora/run-1
```

输出目录须为新目录，包含 `adapter/` 和 `report.json`。脚本从本地路径加载模型，全程不调用 DeepSeek 或上传训练数据。

本机首轮结果保存在 `target/scope-lora/run-1/`。18 条留出题的分派正确数由 5 提升到 14，关键字面量保留数由 14 提升到 18，两项同时通过由 4 提升到 14。其中 3 条基础模型通过的题在微调后分派错误。该适配器保留为实验候选。

## 专职 Bugfix 交接

Root 的可信协调器用管理员凭据读取 `/library/tasks/{taskId}/revisions/{revision}/failure-evidence`，比对失败尝试和摘要。对仍处于 `VERIFYING` 的失败结果，向任务的 `verification` 端点提交 `accepted: false`、`routeToBugfix: true` 及精确 `resultDigest`，并带当前 `If-Match` 和幂等键。控制服务确认剩余尝试次数、Bugfix 能力以及每个修订只交接一次。

管理员通过原凭据接口为 `bugfix` 注册项目绑定的令牌、`hostId` 和 `capabilities`。Bugfix 从 `/bugfix/claims` 领取原任务包、新租约及固定的失败证据。它可操作自己的尝试；Root 的可信验收通过后，Library 读取同一任务修订的已验收修复提交。原失败结果和授权证据保留。

```powershell
./scripts/Start-Platform.ps1
./scripts/Test-AgentPoc.ps1 -SkipBuild -Runs 1 -Bugfix -FixtureModel -ControlUrl http://127.0.0.1:18080
```

该命令用脚本化测试模型驱动 Spring AI Alibaba 的真实工具调用，用 Maven 验证六项断言，并注册本地 Git 候选。去掉 `-FixtureModel` 会改用 DeepSeek，发送固定 Java 样例和失败摘要。两种模式均通过管理员协调器授权交接，修复范围限于样例中的一个表达式。

## 多任务规划与发布

准备 `approved-work.json`，包含 `goal` 和 `units`。每个单元包含简短的 `summary` 和完整的 `task` 模板，字段见 [TaskPackage 样例](../../contracts/examples.json)。每批规划 2 至 4 个工作单元，使用同一项目、计划版本和当前组织版本。可信协调器给每项配置独立任务 ID、次中级负责人、能力、仓库基线、可写路径、预算和已有依赖。任务包的 `dependencies` 可以是空数组。

Root 使用 Spring AI Alibaba 拟定子目标和依赖，再复用原有的三层委派。模型只收到目标、工作摘要、候选 ID 和当前交接指令。新建议的依赖使用 `VERIFIED` 门槛，已有 `INTEGRATED` 门槛保留。依赖门槛控制领取时机，任务的 Git 基线始终取自已批准模板。

启动本机控制服务并构建模块后，在项目根目录执行以下 PowerShell。先设置 `$projectId` 为已配置工作分支的项目，并为规划进程配置 `DEEPSEEK_API_KEY`。4 项计划最多调用 13 次模型，生成的文件可先审阅再发布。

```powershell
$api = "http://127.0.0.1:18080/api/v1/projects/$projectId"
$credential = Import-Clixml (Join-Path $env:LOCALAPPDATA 'JDDA/jdda-admin.credential.xml')
$headers = @{ Authorization = 'Bearer ' + $credential.GetNetworkCredential().Password }
Invoke-RestMethod $api -Headers $headers | ConvertTo-Json -Depth 20 |
    Set-Content target/plan-topology.json -Encoding utf8
& "$env:JAVA_HOME/bin/java.exe" -cp 'platform/agent-poc/target/classes;platform/agent-poc/target/dependency/*' `
    io.jdda.poc.MultiTaskPlanner target/plan-topology.json approved-work.json target/task-batch.json
```

审阅 `target/task-batch.json` 后，提交同一文件。发布端点接受 1 至 16 个完整任务包，按依赖顺序在一个事务内创建并发布。重复提交同一文件时，使用原幂等键获取发布回执。

```powershell
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString('N')
Invoke-RestMethod -Method Post "$api/task-batches" -Headers $headers `
    -ContentType 'application/json' -InFile target/task-batch.json
```

无依赖的任务可以由不同主机槽位并行领取。上游租约过期时保留占位，停止确认及重试验收完成后，下游可重新检查依赖。当前验证使用本机 PostgreSQL、两个逻辑主机及模拟模型。
