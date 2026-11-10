# 执行契约草案 v1

本目录定义任务、执行尝试、租约、结果提交及生命周期约束。组织、审批和事件封套见 [治理契约](GOVERNANCE.md)，Root、Library 与 Bugfix 分工见 [Agent 架构](AGENT_ARCHITECTURE.md)。[OpenAPI](openapi.json) 使用 `x-implemented` 标识已接入控制服务的操作。

## 数据文件与验证

- `execution.schema.json` 使用 JSON Schema 2020-12，包含 TaskPackage、TaskSnapshot、TaskAttempt、TaskLease、ResultSubmission 五类记录。
- `examples.json` 是用于结构验证的合成样例，标识、预算与产物地址仅用于测试。
- `task-transitions.json` 是允许迁移的状态对全集。每次迁移还必须通过下文的权限和事务前置条件。
- `test_contracts.py` 校验 Schema、字段边界、样例及迁移矩阵的内部一致性。事务并发、权限、时间顺序和跨记录关联由后续服务测试覆盖。

在项目根目录执行：

```powershell
python -m venv contracts/.venv
contracts/.venv/Scripts/python.exe -m pip install -r contracts/requirements.txt
contracts/.venv/Scripts/python.exe -m unittest discover -s contracts -p "test_*.py" -v
```

验证器必须启用 FormatChecker，以检查真实日期和 URI 格式。[jsonschema 验证文档](https://python-jsonschema.readthedocs.io/en/stable/validate/)

## 身份、版本与字段语义

| 对象 | 唯一身份 | 规则 |
| --- | --- | --- |
| TaskPackage | projectId、taskId、revision | 发布后不可变，parentTaskId 只表达拆分关系，dependencies 独立表达执行门禁 |
| TaskSnapshot | projectId、taskId、revision | rowVersion 每次修改递增，状态与不可变任务包分别持久化 |
| TaskAttempt | projectId、attemptId | 绑定唯一任务 revision、workerId、hostId、slotId，attemptNumber 从 1 累加 |
| TaskLease | projectId、taskId、revision、leaseEpoch | 每个任务最多一个 ACTIVE 租约，绑定唯一 attemptId，服务端原子分配 |
| ResultSubmission | projectId、attemptId | 服务端比较完整身份及结果内容，首次接受后保存不可变提交与回执 |

整数上限使用 JSON 可精确表达的 2^53−1。时间使用 UTC RFC 3339 且以 Z 结尾。Git Commit 支持完整 SHA-1 或 SHA-256，具体仓库只能使用其自身对象格式。预算金额使用两位小数字符串，预算样例沿用人民币。路径采用仓库相对路径与正斜杠，可带 glob。服务端必须解析真实路径、符号链接并再次检查授权范围。

TaskAttempt 的 ACCEPTED / REJECTED 表示可信验收结论。RESULT_SUBMITTED 表示结果进入服务端 VERIFYING。Worker 上报 PASSED 仅是验收输入。

TaskPackage 增加了原说明样例中省略的 reviewPolicy、inputArtifacts 和依赖 revision，并将预算收拢为 budget 对象。这是 v1 草案字段决定，尚无旧版业务 API 数据需要迁移。

## 跨记录语义约束

以下规则需要数据库与认证上下文，JSON Schema 只约束单条记录的形状。

1. 所有关联对象必须属于同一 projectId。taskId、attemptId 的请求参数必须与数据库绑定关系匹配。Worker 身份由认证上下文确定。
2. 任务父链与依赖 DAG 分别拒绝自引用和环。依赖按 taskId、revision 唯一。同一项目内标识唯一，任务父链不能扩大组织授权。
3. dependency 的 VERIFIED 要求精确 revision 已 SUCCEEDED。INTEGRATED 还要求独立集成记录通过，绑定完整候选 SHA。可选 artifact 表示指定产物要求，存在时校验 artifactId 和摘要。后续消费任何产物均需从权威记录固定其摘要。
4. 发布任务时校验 baseCommit、契约、路径、工具、能力、资源、验收配置与父任务剩余预算。资源不超过实际主机和槽位容量。禁止按逻辑 Agent 数重复分配宿主资源。
5. 有效租约要求 status=ACTIVE、服务端当前时间严格小于 expiresAt，且任务 currentAttemptId、leaseEpoch、revision 与提交完全匹配。时间等于 expiresAt 即过期。issuedAt ≤ lastHeartbeatAt < expiresAt，续租不得突破执行截止时间。
6. TaskSnapshot 在 LEASED / RUNNING 状态必须引用匹配的有效租约。VERIFYING 可以保留 currentAttemptId 供审计，此时租约已释放。其他非运行状态也可保留历史 attemptId，是否允许动作必须核查租约及状态。
7. leaseEpoch 在整个 taskId 生命周期单调递增，跨 revision 也不归零。分配新租约和撤销旧授权均原子递增。回收旧租约不会修改其历史 epoch，拒收依据是任务当前 epoch。
8. resultDigest 为不可变结果 manifest 原始字节的 SHA-256。manifest 含任务身份、结果 SHA 与全部证据摘要，需与提交字段逐项匹配。URI 必须属于已登记的产物存储，读取受服务端访问策略约束。
9. TaskAttempt 在 ACCEPTED、REJECTED、EXPIRED、CANCELLED 时必须有 finishedAt，其他状态 finishedAt 为 null。finishedAt ≥ createdAt。Attempt 状态由同一事务服务更新，重复执行同一 attempt 不新建容器。

## 迁移触发条件

| 迁移 | 触发者与前置条件 |
| --- | --- |
| DRAFT → WAITING_DEPENDENCIES / READY | 获授权的发布者，版本、契约、预算通过，依赖未齐全则等待 |
| WAITING_DEPENDENCIES → READY | 调度器重新检查全部依赖及预算 |
| READY → LEASED | 调度器锁定任务、原子预留预算和槽位、分配 attempt 与 epoch |
| LEASED → RUNNING | 已认证 Worker 确认启动，当前有效租约匹配 |
| RUNNING → VERIFYING | 服务端接受结果与可信停止证据，保存回执，释放 Worker 租约与槽位 |
| VERIFYING → SUCCEEDED | 可信验收器完成指定 acceptanceProfile，绑定精确 SHA 与证据 |
| LEASED / RUNNING / VERIFYING → RETRY_WAIT | 调度或验收失败且预算允许，撤销执行授权，保存失败证据 |
| RETRY_WAIT → READY | 退避到期，旧执行已停止且资源已对账，依赖及剩余预算允许 |
| WAITING_DEPENDENCIES / READY / LEASED / RUNNING / VERIFYING / RETRY_WAIT → BLOCKED | 依赖失效、审批或策略阻塞，活动执行先撤权并停止 |
| BLOCKED → READY | 获授权的解阻决策，旧执行停止，全部门禁和资源重新校验 |
| LEASED / RUNNING / VERIFYING / RETRY_WAIT / BLOCKED → FAILED | 预算耗尽或不可恢复，保存原因，活动执行撤权并确认停止 |
| 任意可取消非终态 → CANCEL_REQUESTED | 授权取消事务，递增 epoch 并撤销 Worker 执行授权 |
| CANCEL_REQUESTED → CANCELLED | 可信执行器停止证明、资源回收完成，服务端验证器也已停止；无活动执行时需确认无待处理派发 |
| DRAFT / WAITING_DEPENDENCIES / READY / RETRY_WAIT / BLOCKED → SUPERSEDED | 新版本已登记，旧执行已停止、无待处理派发、资源已对账 |

矩阵中没有列出的迁移返回 409 INVALID_TRANSITION。SUCCEEDED、FAILED、CANCELLED、SUPERSEDED 为终态。重做需创建新 revision 或新任务。运行中的 revision 替换先走取消，旧 revision 记录为 CANCELLED，再发布新 revision。

租约超时使用上述失败或重试流程。撤权后旧进程可能仍存在，停止确认前保留资源占用并阻止重派。VERIFYING 使用独立验收截止时间，超时走失败、重试或升级。暂停项目只阻止新领取，活动租约按既定续租或取消流程处理。

## 事务、幂等与取消竞态

所有写请求携带 Idempotency-Key。键作用域为已认证主体、projectId、操作类型、资源身份与幂等键。服务端比较校验后的结构化内容，JSON 对象键顺序不影响比较。相同键同内容返回持久化的原 HTTP 状态和回执，相同键不同内容返回 409 IDEMPOTENCY_CONFLICT。不同键再次提交已接受 attempt 的相同结果也返回原回执，内容不同返回 409 RESULT_CONFLICT。

鉴权先于幂等回执读取。已接受结果的重放先于有效租约检查，保证成功提交在租约释放后仍可安全重试。首次提交必须在同一事务中校验有效租约、保存结果和回执、迁移状态并写 Outbox。过期首次提交返回 409 STALE_LEASE。

取消与结果接受锁定同一任务并比较 rowVersion。取消先提交时拒收首次结果。结果先提交时，后续取消可将 VERIFYING 改为 CANCEL_REQUESTED，已存结果仅保留证据。验收先提交 SUCCEEDED 时取消返回 409 TERMINAL_STATE。相同幂等键重放原成功回执不改变当前取消状态。

幂等回执、结果、租约和终态记录在首版保留到显式项目归档清理。审批及归档保留期由后续 Q08 确定。外部 Git、CI、模型调用使用 attempt 关联键和对账，数据库事务覆盖任务状态与 Outbox。

## API 草案

全路径以 /api/v1 为前缀。projectId 参与路径定位并与认证授权范围比对。错误体包含 code、message、traceId、retryable、details。

| 方法与路径 | 请求与成功响应 | 并发约束 |
| --- | --- | --- |
| POST /projects/{p}/tasks | TaskPackage → 201 TaskSnapshot(DRAFT) | 校验任务身份与路径 projectId，同一 revision 内容冲突返回 409 |
| GET /projects/{p}/tasks/{t}/revisions/{r} | 200 TaskPackage 与 TaskSnapshot | 返回 ETag，格式为带引号的 rowVersion |
| POST /projects/{p}/tasks/{t}/revisions/{r}/publish | 空对象 → 200 TaskSnapshot | 必须 If-Match，发布权限与全部语义校验 |
| POST /projects/{p}/worker/claims | 空对象 → 201 TaskPackage、TaskAttempt、TaskLease，暂无任务返回 204 | worker/host/slot 从已登记认证绑定中选择，锁定任务和资源 |
| POST /projects/{p}/attempts/{a}/started | revision、leaseEpoch → 200 TaskSnapshot | 当前认证 Worker 及有效租约 |
| POST /projects/{p}/attempts/{a}/heartbeat | revision、leaseEpoch → 200 TaskLease | 服务端决定 expiresAt，过期租约不可续租 |
| POST /projects/{p}/attempts/{a}/results | ResultSubmission → 202 回执 | 回执含 submissionId、acceptedAt、status=VERIFYING、statusUrl；状态查询使用任务 GET |
| POST /projects/{p}/tasks/{t}/revisions/{r}/cancel | reason → 202 TaskSnapshot | 必须 If-Match，完成响应表示取消意图已保存 |
| POST /projects/{p}/attempts/{a}/stopped | revision、leaseEpoch、停止证据引用 → 200 TaskSnapshot | 允许原拥有者报告已撤销 epoch 的停止证据，仅释放旧执行资源，不能续租或修改新尝试 |

started、heartbeat、results、stopped 通过 revision、attemptId、epoch 围栏进行并发控制。publish 和 cancel 通过 If-Match 实现 CAS。所有写操作均使用 Idempotency-Key。

| HTTP | code | 客户端行为 |
| --- | --- | --- |
| 400 | INVALID_PAYLOAD / UNSUPPORTED_SCHEMA | 修正结构或 Schema 版本 |
| 401 / 403 | UNAUTHENTICATED / FORBIDDEN | 重新认证或取得所需授权 |
| 404 | NOT_FOUND | 核对项目范围与资源标识 |
| 409 | VERSION_CONFLICT / INVALID_TRANSITION / TERMINAL_STATE | 回查状态后决定新操作 |
| 409 | IDEMPOTENCY_CONFLICT / RESULT_CONFLICT / STALE_LEASE | 回查原回执或执行记录，停止旧尝试 |
| 422 | INVALID_DEPENDENCY / BUDGET_EXCEEDED / INVALID_EVIDENCE | 修正语义或进入升级流程 |
| 428 | PRECONDITION_REQUIRED | 补充要求的 If-Match |
| 429 | RATE_LIMITED | 按 Retry-After 退避 |

以上业务冲突错误 retryable=false。网络超时或临时 503 使用原幂等键重试，429/503 的 retryable=true。事件契约和当前范围 OpenAPI 已补齐。身份发行、整份计划管理、Git 集成操作端点仍需后续定义。

## 当前实现（2026-11-09）

OpenAPI 新增项目初始化、项目查询和任务库检索，共 24 个操作。`x-implemented: true` 标记已实现的 8 个操作：项目创建/查询、Agent 注册/查询/改挂、任务草案创建/查询、任务库摘要检索。其余操作仍为设计契约。

管理员、Worker 和 Bugfix 通过独立身份访问各自授权的操作。任务库接口 `/projects/{p}/library/tasks` 支持 q、after、limit 和 maxBytes，返回摘要、版本、详情引用和翻页游标。启动与集成验证见 [项目说明](../README.md)。


### 2026-11-10 运行时更新

共 28 个操作、19 个已实现：新增发布、领取、启动、心跳、结果提交、取消、停止回报、Worker 凭据轮换/撤销、可信结果验证及 audit-events 查询。Worker 端点要求项目绑定身份，不能使用管理员令牌冒充 Worker；管理员确认验收不等价于远端 CI 自动回调或 Git 集成成功。INTEGRATED 依赖目前保持等待，不会用测试通过代替合并证据。未实现自动重试、全局预算预留、SSE 与跨项目仓库路径协调。


### Library CI 更新

新增 3 个管理员接口：POST /projects/{p}/library/repositories/{repo}、POST .../candidates/{sha}/verify-ci、GET .../candidates/{sha}/ci。当前 OpenAPI 共 31 个操作，22 个已实现。验证器固定访问 api.github.com，不接受任意证据 URL；仓库与工作流绑定不可变。结果为历史 CI_PASSED，不能直接当成合并审批或 INTEGRATED 状态。

### 逐层委派

`TaskPackage.delegation` 可记录目标和 Root、中级、次中级到 Worker 的三次交接。创建与发布时核对拓扑版本、直属关系、角色状态、管理者、Worker 主机及所需能力。有委派记录时，最后一跳指定的 Worker 才能领取。已有任务和手工创建的任务可省略该字段，沿用按直属管理者和能力领取的规则。

### Library 整合候选

`GET /projects/{p}/library/tasks/{t}/revisions/{r}/accepted-result` 返回验收成功的来源提交、基线、摘要和路径授权。受信任的本地 Git 运行器通过 `POST /projects/{p}/library/integration-candidates` 登记有序来源和候选 SHA，服务再次核对来源归属及摘要。`GET /projects/{p}/library/integration-candidates/{sha}` 返回候选及同项目、同仓库、同 SHA 的 CI 记录。当前共有 34 个操作，25 个已实现。候选登记和历史 CI 核验属于合并前证据，合并审批与远端更新仍需单独完成。

### Library 发布门禁

候选下的 `release` 查询提供审批、推送意图和确认状态。`release/approve` 核对成功 CI、候选授权路径和远端旧基线，`release/begin` 复验相同 CI 运行次数和旧基线并登记 MERGING。`release/confirm` 从 GitHub 核对目标引用并记录 INTEGRATED。依赖门禁只接受已完成确认的来源任务 revision。当前共有 39 个操作，30 个已实现。现阶段由本机管理员调用，使用 Git 执行目标引用的预期基线条件更新。
