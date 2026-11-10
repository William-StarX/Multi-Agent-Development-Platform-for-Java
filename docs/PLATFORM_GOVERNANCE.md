# JDDA 运行治理

控制服务可订阅 Nacos 中的一份 JSON 配置。项目并发配额由 PostgreSQL 中的领取事务执行。操作者在 Nacos 发布或回滚规则，通过管理 API 核对结果。生成项目有各自独立的 Nacos 策略，选用 Sentinel 的模块可以配置业务限流和熔断。[生成项目配置](PROJECT_GENERATION.md) · [Nacos Java SDK](https://nacos.io/en/docs/latest/manual/user/java-sdk/usage/)

## 接入

在 Nacos 3.3 中创建配置。默认 Group 为 `JDDA`，Data ID 为 `jdda-governance.json`，内容类型选择 JSON。Namespace 可按环境单独设置。

```json
{
  "maxActiveAttempts": 8,
  "projectLimits": {"demo-java": 2}
}
```

给控制服务进程设置以下环境变量，然后启动或重启 JDDA。部署环境负责注入 Nacos 只读账号凭据。发布权限可分配给独立的 Nacos 运维账号。

| 环境变量 | 用途 |
| --- | --- |
| `JDDA_NACOS_ENABLED` | 设置为 `true` 启用订阅。 |
| `JDDA_NACOS_SERVER_ADDR` | Nacos SDK 地址，例如 `127.0.0.1:8848`。默认 gRPC 客户端端口为 9848，远程部署需允许 SDK 访问。 |
| `JDDA_NACOS_NAMESPACE` | 可选的命名空间 ID，默认公共命名空间。 |
| `JDDA_NACOS_GROUP`、`JDDA_NACOS_DATA_ID` | 覆盖默认的 Group 和 Data ID。 |
| `JDDA_NACOS_USERNAME`、`JDDA_NACOS_PASSWORD` | 读取上述配置的 Nacos 账号。 |

启用后，控制服务在启动时要求读到有效规则，随后每 5 秒核对配置。有效配置会热更新。发布格式错误或删除配置时，进程继续采用最近一次有效规则，并写入拒绝日志。数据库连接、管理员令牌和模型密钥通过各自的部署环境管理。

## 规则含义

| 字段 | 行为 |
| --- | --- |
| `maxActiveAttempts` | 每个项目的默认并发尝试数。到上限时领取返回 `204`，已有尝试继续续约和提交。过期且尚未确认停止的尝试仍占槽。 |
| `projectLimits` | 按项目 ID 覆盖默认并发上限。值为 `0` 表示不设该级上限。 |

并发上限允许 `0` 到 `10000`，`0` 表示不限。完整配置可用 `{}` 恢复默认行为。控制台的暂停派发开关可以独立使用。从早期版本升级时，先在 Nacos 移除旧的 `claimQps` 和 `githubCircuit` 字段。

用管理员凭据读取当前规则。响应包含 `source`、实例内递增的 `revision`、`appliedAt` 和 `settings`。发布和回滚后可据此确认生效。

```powershell
. .\scripts\PlatformSettings.ps1
$control = Resolve-JddaControlUrl
$headers = @{ Authorization = 'Bearer ' + (Get-JddaAdminToken -ControlUrl $control) }
Invoke-RestMethod "$control/api/v1/governance" -Headers $headers -MaximumRedirection 0
```

Nacos 配置按进程生效。多实例部署时应核对每个实例的规则版本。控制台的配对和会话当前绑定单个控制进程。部署地址和凭据见 [平台部署](PLATFORM_SETUP.md)。
