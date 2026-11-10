# 治理与事件契约草案 v1

本批次补齐组织节点、精确对象审批、故障升级和类型化事件封套。`openapi.json` 使用 OpenAPI 3.1，描述执行契约与本批治理契约的 21 个操作。项目创建、整份计划管理、身份令牌发行和 Git 集成服务的操作接口仍需后续定义。当前运行服务提供健康检查，业务端点在后续实现阶段接入。

## 文件与验证

`governance.schema.json` 引用既有执行 Schema 的标识、时间、摘要、预算和产物定义。`governance-examples.json` 提供五类服务端记录样例。`openapi.json` 直接引用两份 Schema，客户端可以据此生成接口类型。

```powershell
contracts/.venv/Scripts/python.exe -m pip install -r contracts/requirements.txt
contracts/.venv/Scripts/python.exe -m unittest discover -s contracts -p "test_*.py" -v
```

执行与治理测试通过上述 discover 命令统一运行。以下涉及数据库、认证、权限和时间的规则是后续实现验收条件。

## 组织与生命周期

每个项目有且只有一个活动 ROOT。ROOT 的 parentId 为 null，其余节点必须有父节点。roles 使用 ROOT、MANAGER、WORKER、LIBRARY、BUGFIX。普通任务节点可组合 MANAGER 和 WORKER；LIBRARY 与 BUGFIX 分别只能使用独立单角色，直属 Root。Bugfix 必须是叶子节点。层数沿父链计算。专职分工与协作边界见 [Agent 架构](AGENT_ARCHITECTURE.md)。

每次变更锁定项目拓扑版本，检查同项目、父节点具有 ROOT 或 MANAGER 角色、无环、无孤儿、深度和数量限额。每个项目配置一个 Library 和一个 Bugfix，二者直接挂到唯一 Root 下。禁止以 Bugfix 为任何节点的 parentId。登记节点时，endpoint、hostId、capabilities 必须与可信注册资料核对。任何父子关系都必须满足委派权限交集。

修改父节点请求使用 If-Match 比较节点 rowVersion，同时比较正文 topologyVersion。首版拒绝移动存在活动任务、未停止执行或待对账资源的节点及其子树，返回 409 ACTIVE_EXECUTION。唯一 ROOT、父链和版本更新在同一事务完成。固定任务 topologyVersion 和升级路径保留原始记录，权限撤销立即生效。

| 原状态 | 新状态 | 前置条件 |
| --- | --- | --- |
| ACTIVE | DRAINING | 获授权的排空请求，停止新增领取 |
| DRAINING | DISABLED | 活动尝试和待对账资源均为零，子节点已合法迁移或停用 |
| DRAINING | ACTIVE | 撤销排空且身份、主机、权限检查通过 |
| DISABLED | ACTIVE | 有效父节点、身份和容量检查通过，维护唯一 ROOT |

其余状态变化返回 409 INVALID_TRANSITION。相同幂等请求返回原回执。处于排空或停用状态的 Worker 不能领取新任务。Root 停用需项目停机处置，首版在线停用接口返回 409 ROOT_REQUIRED。

## 审批与版本绑定

ApprovalScope 固定 operation、objectId、objectVersion、contentDigest。INTEGRATION_MERGE 额外固定完整 candidateCommit，其他操作 candidateCommit 为 null。contentDigest 指向已保存、可复核的不可变对象内容。合并对象还必须包含仓库、目标分支、目标基准 SHA、候选 SHA、CI 证据摘要。

requestedBy、requestedAt、decidedBy、decidedAt、rowVersion 均由服务端写入。审批请求与决策请求使用独立 Schema，额外的身份或状态字段被拒绝。请求审批不会赋予审批权限。

| 原状态 | 新状态 | 条件 |
| --- | --- | --- |
| PENDING | APPROVED / REJECTED | 审批者授权有效，If-Match 匹配，服务端时间小于 expiresAt，对象仍与 scope 一致 |
| PENDING / APPROVED | EXPIRED | 服务端时间大于或等于 expiresAt |
| PENDING / APPROVED | INVALIDATED | 对象、摘要、候选提交变化，或审批权限被撤销 |

REJECTED、EXPIRED、INVALIDATED 的记录不可重新批准。修改对象后创建新的审批请求。执行获批操作时再次核验权限、有效期、scope 和可信 CI，在同一事务中登记唯一消费记录与操作 Outbox。消费标识使用 projectId、approvalId、scopeDigest，相同操作可重放既有结果，不能重复执行不同操作。数据库写入与外部 Git 操作之间通过精确候选和外部对账衔接。

Task publish 按 reviewPolicy 查找匹配的有效 PLAN_PUBLISH 审批。预算调整与契约发布分别要求对应 scope。集成审批使用统一 approvals 接口表达，集成服务在执行合并时消费该审批。集成动作 API 仍待定义。

## 故障与逐级升级

IncidentReport 包含执行身份、错误类型、指纹、失败测试、日志摘要、尝试动作、阻塞依赖、剩余预算和请求决定。服务端从认证主体填写 reporterId，从冻结拓扑推导 escalationPath。remainingBudget 以权威账目重算，Worker 的值用于核对。

报告必须属于调用者实际拥有的 attempt。已撤权 attempt 的迟到报告可保存为审计证据，不能阻塞或重排新的尝试。firstSeenAt ≤ lastSeenAt，且时间与原执行窗口合理匹配。错误指纹由可信服务复核，不能通过更换 incidentId 或错误描述重置累计预算。

escalationPath 按直接父级到 Root 排列，不包含报告节点，节点唯一，最多受拓扑深度上限约束。任务分配时固定父链。没有可用父链时直接进入 AWAITING_HUMAN。escalationIndex 从 0 开始，表示当前处理位置，取值不能超过父链长度。assignedTo 必须匹配当前位置，人工出口由人工队列承接。

| 原状态 | 新状态 | 触发条件 |
| --- | --- | --- |
| OPEN / ESCALATED | ACKNOWLEDGED | 当前负责人确认，If-Match 匹配 |
| OPEN / ACKNOWLEDGED / ESCALATED | ESCALATED | 当前级无权处理、明确上交或超时，索引只增加 |
| OPEN / ACKNOWLEDGED / ESCALATED | AWAITING_HUMAN | 父链耗尽或安全策略要求人工处理 |
| OPEN / ACKNOWLEDGED / ESCALATED / AWAITING_HUMAN | RESOLVED | 授权处置完成，关联动作与资源状态已确认 |

ackDeadline ≤ decisionDeadline。初次与上移一级时按当时服务器时间设置限额内的新截止时间。确认接手或 REQUEST_INFO 不延长已确定的 decisionDeadline，避免无限等待。超时扫描采用 CAS，同一 incident、升级索引至多创建一次升级记录。

IncidentDecision 保留 actorId、incidentVersion、reason、deadline 和 relatedTasks。决策请求不能携带 actorId。RETRY、REPAIR、REASSIGN、CONTRACT_CHANGE 只建立待执行的处置动作，必须重新核查预算与权限。收到 HTTP 成功仅表示决策持久化，RESOLVED 由后续动作结果驱动。安全事件直接进入人工出口。终态任务的处置使用新 revision 或关联修复任务。处置过程中同一事件不能倒退升级索引或重新创建事件以规避上限。

## 事件与 SSE

支持 TASK_STATE_CHANGED、AGENT_CHANGED、APPROVAL_CHANGED、INCIDENT_CHANGED。payload 分别绑定 TaskSnapshot、AgentNode、Approval、Incident。TASK_STATE_CHANGED 的 revision 为正整数，其他事件 revision 为 null。

eventId 在项目内唯一。payload.projectId 必须等于封套 projectId，aggregateId 必须等于载荷的 taskId、agentId、approvalId 或 incidentId。任务事件的 revision 也必须一致。aggregateVersion 是该聚合事件流的连续序号，与载荷 rowVersion 分别存储。封套不能用旧状态伪装新的序号。

状态、Outbox 和聚合事件序号在同一事务提交。投递允许重复或乱序，消费者先按 projectId、eventId 去重，再比较 aggregateVersion。检测缺口时回查权威状态，旧事件只用于审计和补齐记录。

SSE 使用 id、event、data 三个字段，data 是单行 JSON EventEnvelope。Last-Event-ID 定位项目内重放位置。服务端使用持久化 Outbox 顺序读取已提交事件，禁止以数据库序列预分配值推断提交顺序而跳过晚提交事件。后台发布游标必须保证已返回游标之前没有尚未纳入流的事件。

新订阅从建立订阅时的当前游标之后开始。客户端先建立订阅并缓冲事件，再读取相关资源快照，按事件版本去重和补齐。资源列表与全量快照接口在相应管理功能实现时补齐。重连提交 Last-Event-ID，返回之后的事件。跨项目或不存在的游标返回 400 INVALID_EVENT_CURSOR，保留期外游标返回 410 EVENT_CURSOR_EXPIRED。服务端返回过期后客户端重新订阅并读取快照。

订阅连接建立及重放均检查项目权限，连接期间定期复查授权，撤销后关闭流。事件中仅提供产物引用及摘要。密钥、完整原始日志和模型上下文保留在各自受控存储中。

## API 共同约束与实现验收

认证主体由服务端确认，projectId 路径值只用于查找授权范围。请求正文中的 projectId 必须与路径匹配。所有写操作要求 Idempotency-Key。组织状态、父关系、审批决策与故障确认/决策要求 If-Match。

补充错误码包括 ACTIVE_EXECUTION、ROOT_REQUIRED、INVALID_TOPOLOGY、APPROVAL_EXPIRED、APPROVAL_STALE、INVALID_INCIDENT_ROUTE、INVALID_EVENT_CURSOR、EVENT_CURSOR_EXPIRED。结构错误使用 400，缺少 If-Match 使用 428，版本/状态/审批冲突使用 409，图结构和关联语义错误使用 422。业务冲突 retryable=false，429 与 503 可以使用原幂等键退避重试。

后续事务测试至少覆盖同时创建两个 Root、成环重挂、跨项目父节点、活动子树重挂、伪造审批人、过期审批、替换候选 SHA 后复用审批、同级升级并发重复、旧 attempt 迟到报告、错误项目载荷、乱序与重复事件、SSE 游标重放。Schema 测试只验证字段及本地条件，以上行为需真实服务与数据库验证。

本轮选用的 OpenAPI 3.1 Schema 方言兼容 JSON Schema 2020-12。[OpenAPI 规范](https://spec.openapis.org/oas/v3.1.0.html)
