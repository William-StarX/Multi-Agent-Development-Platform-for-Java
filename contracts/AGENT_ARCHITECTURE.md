# 总控、任务库与 Bug 修复 Agent

本设计落实用户于 2026-11-09 指定的专职分工及 2026-11-10 修正的任务层级。Root 负责总控和调度，直属的 Library Agent 维护全量任务、索引及 Git 整合，Bugfix Agent 与 Library 同层。普通任务按中级、次中级、低级逐层分配；次中级负责一个明确的工作范围，低级负责其中可验收的子任务。模块/包是 Java 开发的划分方式，不作为数据库、Nacos 等工作的固定结构。

## 管理关系与协作关系

```text
Root 总控 Agent
├── Library 任务库与代码整合 Agent
├── Bugfix 专职修复 Agent
└── 中级 Agent（MANAGER：协调工作范围及跨范围依赖）
    ├── 次中级 Agent（MANAGER：订单模块）
    │   ├── 低级 Agent（WORKER：订单 controller 包）
    │   ├── 低级 Agent（WORKER：订单 service 包）
    │   └── 低级 Agent（WORKER：订单 repository 包）
    └── 次中级 Agent（MANAGER：库存模块）
        ├── 低级 Agent（WORKER：库存 service 包）
        └── 低级 Agent（WORKER：库存 repository 包）
```

| 节点 | 负责事项 | 协作对象 |
| --- | --- | --- |
| Root | 目标分解、优先级、资源与预算决策、全局调度、处理升级 | Library、Bugfix、普通任务管理分支 |
| Library | 全量任务记录与索引、证据归档、按需检索、Git 候选集成及 CI 证据关联 | Root、Bugfix、获授权的任务提交者 |
| Bugfix | 失败复现、根因分析、专门修复、回归测试与修复提交 | Root、Library |
| 中级 | 划分工作范围、分配次中级、协调跨范围契约与依赖、汇总结果 | Root、下属次中级、授权的 Library 工具 |
| 次中级 | 负责指定模块或其他工作范围，拆分子任务交给低级，协调接口并组织范围内验收 | 上级中级、下属低级、授权的 Library 工具 |
| 低级 | 完成明确的包、数据库设计、迁移、配置等子任务，提交变更及验收证据 | 上级次中级、授权的 Library 工具 |

中级与次中级沿用 MANAGER 角色，用 parentId 表达层级；低级使用 WORKER。普通实现任务不得跳过次中级管理层直接由中级分给低级。组织模板见 agent-topology.example.json；其中 workAssignments 是分工示例，不是新增的 Agent 注册字段。

模块对应仓库中的实际模块目录，包对应模块内 Java 包及其源码路径；示例名称不限定实际项目结构。包级任务在 TaskPackage.ownedPaths 中声明获授权的源码和测试路径，managerId 指向负责该模块的次中级。共享配置、公共类、跨包修改由次中级协调；跨模块问题上报中级，必要时再到 Root。同一文件不得同时授予多个执行任务写权限。Library 统一整合提交，次中级负责模块验收而非另建 Git 整合权威。

对于非 Java 工作，可按以下方式划分；具体粒度取决于任务，不要求伪造模块目录或 Java 包：

| 工作类型 | 次中级负责范围 | 低级子任务示例 |
| --- | --- | --- |
| Java | 业务模块 | controller、service、repository 包及其测试 |
| 数据库 | 某库或业务数据域 | 表结构、索引、迁移脚本、备份恢复验证 |
| Nacos | 服务治理或配置管理范围 | 注册发现、配置集、环境隔离与权限验证 |

任务需要明确负责人、输入、输出、依赖、验收标准和可写资源。文件变更使用 ownedPaths；数据库/Nacos 等外部资源另需实例、库/Schema、namespace/group/dataId 等目标范围及工具权限，不把文件路径授权视为外部系统写权限。当前示例只定义分工，不授权修改现有数据库或 Nacos 配置，也不表示相应工具权限已经实现。

现有 API 支持嵌套 MANAGER，并校验父节点、环路和版本。规划样例使用 Spring AI Alibaba `StateGraph` 和 `ReactAgent`，按 Root、中级、次中级顺序生成委派。控制服务对带 `delegation` 的任务强制校验三跳直属关系，最后一跳指定的 Worker 才能领取。数据库和 Nacos 等外部资源的授权仍待接入。[Spring AI Alibaba 工作流](https://www.java2ai.com/docs/frameworks/agent-framework/advanced/workflow/)

管理树使用 parentId。Library 和 Bugfix 都直接挂在 Root 下。Bugfix 是叶子节点，禁止挂接任何子 Agent。Bugfix 的 Agent 间调用仅允许 Root 与 Library，普通任务 Agent 的故障经任务库记录和 Root 调度转交。Bugfix 可以通过受控工具访问修复工作区和测试服务。

Library 是独立专职管理节点。首版每个项目配置一个 Library 和一个 Bugfix，两个专职角色分别使用独立 Agent。Library 以工具接口接收任务证据和代码提交，其协作连接不授予对调用方的管理权限。

## 持久化与调度

全量任务包、状态、执行历史、错误、索引、代码引用及产物关系保存到持久化服务。Library 通过授权工具维护和检索。PostgreSQL 保持任务状态权威，Git 保存代码与提交历史，产物存储保存大日志和测试报告。

Root 作出调度决策后，确定性控制服务执行权限检查、DAG 检查、预算预留、租约分配与状态事务。Library 的检索索引是可重建投影。索引落后时返回索引版本与来源版本，Root 发起调度前由控制服务回查权威状态。

Library 的对话中只保留当前操作所需片段。节点重启从数据库、Git 和索引恢复。Git 整合按仓库和目标分支串行构造候选，Library 提交候选及验证请求，可信 CI 和审批服务决定是否满足合并条件。

## 按需上下文

Root 常驻上下文保留目标、活动计划版本、任务数量与状态汇总、关键阻塞、剩余预算和待决事项。

Library 的查询结果包含 taskId、revision、rowVersion、简短摘要、来源版本和 detailRef。默认分页返回摘要，Root 仅在决策需要时按 detailRef 读取指定任务、失败测试、代码片段或契约。响应带分页游标与截断标记，调用者明确限制条数和文本字节预算。

摘要属于有来源的派生信息。调度、验收与合并使用完整权威记录和精确提交。上下文节省量在 M1 POC 中记录实际输入 Token、索引命中率、回查次数和漏取关键信息的情况。

## Bug 修复闭环

1. 普通执行者将失败证据、测试结果、代码 SHA 和错误指纹提交至任务服务，Library 完成索引。
2. Root 按严重性、重复失败和剩余预算生成专门修复任务，交给 Bugfix，费用继续计入原任务树。
3. Bugfix 向 Library 读取失败快照、契约、相关代码及日志引用，在独立 attempt 工作区复现和修复。
4. Bugfix 将修复提交、回归测试和影响范围交给 Library，并向 Root 回报摘要及证据引用。
5. Library 构造精确集成候选并关联 CI。候选验证及所需审批通过后，集成服务执行合并。
6. Bugfix 超预算或无法解决时向 Root 升级，Library 保留完整失败链。跨任务协调由 Root 下发给对应分支。

## 契约与验收落点

治理 Schema 增加独立角色 LIBRARY 和 BUGFIX。它们分别只能作为单角色节点。是否直属 Root、项目内是否唯一、Bugfix 是否无子节点及通信是否越界，需要注册、拓扑变更和调用授权服务校验。

验收覆盖 Library / Bugfix 直属同一 Root、禁止给 Bugfix 添加子节点、拒绝 Bugfix 直接调用普通任务 Agent、Root 与 Library 的摘要和详情调用、Bugfix 修复后交回 Library 整合、Library 重启后索引恢复、陈旧索引不能放行错误调度、旧候选 CI 不能批准新候选。

本机实现由可信 Root 协调器在失败验收时固定原结果摘要并授权交接。Bugfix 在独立队列领取同一任务修订的新尝试，沿用原始 Git 基线、资源范围和最大尝试次数。Library 提供失败证据引用，并以通过可信验收的修复提交构造集成候选。当前端到端验证范围为受控 Java 样例，自动升级策略和通用项目修复仍待验收。
