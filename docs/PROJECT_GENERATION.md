# 生成项目的维护手册

## 设计清单

项目清单的 `schemaVersion` 为 `1`。`project` 是 Nacos Group 和 Git 仓库标识，`group` 是 Java 根包。每个 `modules` 条目产生独立 Maven 模块和 Docker 镜像。模块名使用小写连字符并以 `-service` 结尾。设置 `replicas` 选择 1 到 8 个副本，`routes` 声明互不重叠的 `/api/…` 前缀。[预留验收清单](../examples/projects/reservations.json) · [路由验收清单](../examples/projects/service-routing.json)

| 选项 | 行为 |
| --- | --- |
| `ingress: direct` | 服务在 Compose 私网使用 `http://<模块名>:8080`，供内部调用。验收期间临时通过回环探针检查各副本。 |
| `ingress: nginx` | Nginx 用 Docker DNS 解析服务副本，按声明的路由转发。 |
| `ingress: gateway` | 独立 Spring Cloud Gateway MVC 模块，生成明确路由与连接、读取超时。 |
| `features: postgres` | 独立 PostgreSQL、应用账号、JDBC 与 Flyway。初始 SQL 写在该模块的 `migration`。 |
| `features: redis` | Redis 读穿缓存。缓存不可用时回退到加载函数。 |
| `features: rabbitmq` | 依赖同模块的 PostgreSQL。支持事务发件箱、发布确认、消费去重及死信队列。 |
| `features: sentinel` | 实例内请求限流和下游异常比例熔断。 |
| `features: openfeign` | 启用该模块的 `@FeignClient` 扫描并设置连接和读取超时。客户端接口由对应任务生成。 |

`tasks` 中的每项工作需要填写 `id`、`module`、相对于模块根包的 `package`、`files`、`goal` 和 `constraints`。约束应明确跨包类型、方法签名、错误协议和事务边界。路径独占。Worker 只能新增这些 Java 文件，Docker、Nacos、POM、迁移和 `runtime` 包由维护者审核的基线提供。

使用 `python -B scripts/Generate-Project.py --manifest <清单> --plan-only` 查看分派范围，或用 `--output <新目录>` 单独渲染骨架。`Invoke-ProjectGeneration.ps1` 另外创建 Git 基线、调用已配置的 Agent，并用 Library 生成精确候选。修订已整合的候选时，将维护者确认的诊断写成任务 ID 到文字的 JSON 对象，传给 `-Resume -Repair <文件>`。旧候选报告和未受影响的 Worker 提交均保留。

## Nacos 运行策略

默认 Namespace 为 `public`，Group 为项目清单的 `project`，Data ID 为 `<模块名>.json`。清单中的模块 `policy` 可设置初始值，渲染后位于 `ops/nacos`。服务账号只读取自己的配置。运行中的有效策略无需重启，格式错误或越界时保留最近一次有效版本。

| 字段 | 生效行为 |
| --- | --- |
| `maxInFlight` | 单实例在途 API 请求数，1 到 1000，默认 64。超出返回 `429 CONCURRENCY_LIMIT` 和 `Retry-After`。 |
| `apiQps` | Sentinel 单实例每秒请求数，0 到 10000。0 关闭，超限返回 `429 TRAFFIC_LIMIT`。 |
| `cacheTtlSeconds` | Redis 缓存秒数，0 到 3600。0 跳过读写缓存。 |
| `circuitMinRequests` 与 `circuitOpenSeconds` | Sentinel 统计窗口内的最少调用量和打开秒数。范围分别为 0 到 1000 和 0 到 300。两项一起启用或置零。预设使用 10 秒窗口和 50% 异常比例。 |
| `parameters` | 可选的模块自定义非敏感字符串配置，最多 16 项。业务代码通过 `RuntimePolicy.current().settings().parameters()` 读取，和并发策略采用同一配置快照。 |

服务每 5 秒从 Nacos 核对配置。 `X-JDDA-Config` 提供生效配置内容的 SHA-256，`X-JDDA-Instance` 标识实例。发布后按实例核对响应头，必要时重新发布上一份配置。副本数、入口路由、连接凭据和数据迁移通过清单与部署环境管理。

## 持久部署与配置发布

下面使用已通过验收的本地生成目录。为 Compose 中 `${…:?…}` 引用的变量注入各自凭据，服务读账号与写接口令牌按模块独立设置。`NACOS_AUTH_TOKEN` 使用 Base64 编码的随机字节。`ProjectNacos.py initialize` 读取当前进程的 `<MODULE>_NACOS_PASSWORD`，交互提示设置管理员密码。

```powershell
$project = 'target/generated-projects/reservations-demo'
$stack = 'reservations-dev'
$compose = @('compose', '-p', $stack, '-f', "$project/compose.json")
$env:JDDA_SOURCE_SHA = git -C $project rev-parse HEAD
docker @compose up -d nacos
$nacos = 'http://' + (docker @compose port nacos 8848)
python -B scripts/ProjectNacos.py initialize --project-dir $project --address $nacos
docker @compose up -d --build --wait
docker @compose port edge 8080
```

初始化适用于新建的项目专用 Nacos 实例。已有实例由维护者保留其账号和策略，从 Nacos 管理接口核对该项目的读权限及 Data ID。应用依赖已发布且有效的初始配置才能就绪。回环入口地址和配置均从当前 Compose 项目读取。

```powershell
python -B scripts/ProjectNacos.py read --project-dir $project --address $nacos --module allocation-service > policy.previous.json
python -B scripts/ProjectNacos.py publish --project-dir $project --address $nacos --module allocation-service --config policy.next.json
```

将 `policy.previous.json` 用于回滚。`publish` 输出配置摘要，可和服务响应头对比。也可通过 Nacos 控制台的配置历史发布或回滚。[Nacos 管理 API](https://nacos.io/docs/latest/manual/admin/admin-api/) · [Sentinel 流控与熔断](https://sentinelguard.io/en-us/docs/circuit-breaking.html) · [Spring Cloud 版本对应关系](https://spring.io/projects/spring-cloud/)

## 自定义验收

`--suite smoke` 只核对 Git 候选、镜像及容器就绪。真实项目可用 `--workload <脚本路径>` 执行自己的可信验收。维护者在生成仓库之外保存该脚本。运行器在宿主机执行其中的 `verify(deployment, request, eventually, require)`，并把返回的测量结果和脚本摘要写入报告。[Gateway 与 Feign 验收脚本](../examples/projects/verify-service-routing.py)

验收栈使用本次生成的随机凭据和独立网络、数据卷。验收器按固定的 Git SHA 构建，结束后清理该栈。`--promote` 需要通过负载或自定义验收并确认目标分支仍在原基线。需要远端发布时，再走仓库自己的 CI 与审批。[候选发布](RELEASE_RUNBOOK.md#发布已审查候选)
