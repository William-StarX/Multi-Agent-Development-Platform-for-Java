# 生成项目验收记录

2026-11-11 在 Windows Docker Desktop 的 Linux amd64 容器中执行。MADPJ 的 Root、中级、模块次中级和包级 Worker 使用已配置的 DeepSeek 生成 Java 源码。每个 Worker 从同一份基线提交授权文件，Library 按任务顺序合并。验收器检查 Git 提交图、源文件范围、基线内容和每个副本的镜像标识，再运行负载。通过后将各自的本地验收分支快进到候选 SHA。

## 双副本并发

使用 [预留验收清单](../examples/projects/reservations.json) 和 [验收负载](../scripts/Verify-ReservationWorkload.py)。对同一资源发起 256 个写请求，执行器最多同时发出 64 个。每个预设均在两个副本处理请求，成功 100 次，超出容量的 156 次返回业务拒绝，非预期失败为 0。64 次相同幂等键重试只产生一条预留记录。

| 预设 | 候选 SHA | P95 | P99 | 请求每秒 |
| --- | --- | ---: | ---: | ---: |
| 完整 | `319d78c63a2049b49d478f130fe04c13f837406b` | 819.59 ms | 1170.50 ms | 197.1 |
| 精简 | `d0c22954a405973e08f8211b0c0f0229879a1a4c` | 672.80 ms | 962.45 ms | 245.3 |

两个预设均通过未认证写入阻拦、数据库余量核对、Nacos 热更新、回滚和非法配置保留上个版本。把在途上限调为每实例 1 后，完整预设和精简预设分别返回 38 和 39 次限并发响应。

完整预设另验证 Redis 停机后的读取回退、RabbitMQ 停机期间的事务发件箱、恢复投递与重放去重，以及 Sentinel 限流、熔断和恢复。Nginx 在停掉一个副本后 3.94 秒内连续完成 12 次成功读取。较早的切换演练曾观察到短暂 HTTP 504，验收使用 15 秒内恢复持续读取作为通过条件。

## 路由和业务参数

候选 `41bff10e15e6effa31ffaee996ee4b3b2ba70113` 通过 Gateway 路由白名单、OpenFeign 跨服务请求和上游停机恢复验证。Nacos 发布目录服务的新参数后，调用方通过 OpenFeign 读到新值，调用方自身的配置版本保持原值。[路由清单](../examples/projects/service-routing.json) · [验收脚本](../examples/projects/verify-service-routing.py)

## 平台验证

平台本地通过 38 项 Java 测试和 14 项契约测试。独立发布副本完成 Maven 打包，控制服务产物包含 Nacos 客户端，Sentinel 仅在生成项目选择对应预设时引入。

上述指标对应单主机 Compose 环境的样例负载。业务项目可以用仓库外的可信脚本替换该负载，定义自己的成功率、延时、容量和故障演练标准。[自定义验收](PROJECT_GENERATION.md#自定义验收)
