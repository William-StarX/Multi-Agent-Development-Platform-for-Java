# 部署与连接 JDDA

## Windows 本机

环境需要 PowerShell 7、JDK 21、Maven、Git，以及已创建的 PostgreSQL 应用库和专用登录角色。数据库默认使用 `127.0.0.1:5432/jdda_local` 和角色 `jdda_local`。新环境由数据库管理员按项目 README 的「新环境数据库」初始化。

```powershell
.\scripts\Deploy-Platform.ps1 -OpenConsole
.\scripts\Set-PlatformModel.ps1
```

部署从源码构建两个 Maven 模块，启动控制服务并等待数据库健康检查通过。已构建的安装可以传 `-SkipBuild`。默认数据库在首次部署时提示输入应用角色密码，健康检查通过后保存为当前 Windows 用户的加密凭据。服务首次启动生成随机管理员令牌，控制台使用短期配对建立会话。重复部署复用已配置的凭据。

模型配置只在终端中提示输入 API Key。默认模型接口为 `https://api.deepseek.com`，模型为 `deepseek-flash`。其他 OpenAI 兼容模型通过 `-BaseUrl` 和 `-Model` 指定，例如下面的命令。

```powershell
.\scripts\Set-PlatformModel.ps1 -BaseUrl 'https://models.example.com/v1' -Model 'example-chat'
```

API 基础路径可以包含 `/v1` 等前缀，客户端会添加 `/chat/completions`。Windows 加密存储绑定当前用户，保存的模型密钥绑定配置时的基础 URL。部署与保存配置在本机完成。可运行 `./scripts/Test-PlatformModel.ps1` 用固定提示验证模型连通性。

## 服务部署环境

| 设置 | 用途 |
| --- | --- |
| `JDDA_BIND_ADDRESS`、`JDDA_PORT` | 控制服务监听地址和端口，默认 `127.0.0.1:18080`。 |
| `JDDA_CONTROL_URL` | 控制台启动器和仓库任务客户端访问的站点根地址。远程连接使用 HTTPS 入口。 |
| `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`JDDA_DB_PASSWORD` | 控制服务的数据源及应用角色密码，由部署环境注入。 |
| `JDDA_ADMIN_TOKEN` | 管理员 Bearer 凭据，至少 32 字符。连接远程实例或不同本机端口的实例时，在客户端进程注入对应令牌。 |
| `JDDA_MODEL_BASE_URL`、`JDDA_MODEL`、`JDDA_MODEL_API_KEY` | 仓库任务执行端的模型基础地址、模型和凭据。凭据通过部署环境注入执行端。 |

如需按角色选用供应商，可设置 `JDDA_<ROLE>_MODEL_BASE_URL`、`JDDA_<ROLE>_MODEL`、`JDDA_<ROLE>_MODEL_API_KEY`。角色名为 `ROOT`、`MIDDLE`、`SCOPE`、`WORKER` 或 `BUGFIX`。

可用 `Open-PlatformConsole.ps1 -ControlUrl https://jdda.example.com` 临时覆盖客户端地址。远程站点通过部署环境的反向代理终止 TLS。控制服务可与模型执行端分别运行，服务端使用数据库凭据，独立 Worker 使用其项目绑定凭据调用控制服务。生产环境通过服务管理器注入凭据并管理重启。
