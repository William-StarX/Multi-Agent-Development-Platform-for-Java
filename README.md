# Multi-Agent-Test

JDDA 的独立 Java 业务测试仓库。当前示例实现非负 long 金额的折扣计算，包含 6 个验收测试。

本机及 CI 使用同一命令：`mvn -B -ntp verify`，需要 JDK 21。GitHub Actions 对 push、pull_request 和手动触发执行测试，固定 Action 提交，仓库权限只读。模型密钥不需要进入本仓库或 CI。

此初始版本为通过测试的基线。后续 Agent 变更应在独立分支提交，由对应候选 SHA 的 CI 验证，不直接修改受保护分支。
