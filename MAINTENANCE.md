# 接手评估与 1.3.0-SNAPSHOT 预览版

评估日期：2026-11-02。基于上游 `21e1616`（2025-07-01）。本次为本地维护分支，未向原仓库推送，也不代表原作者发布。

## 判断

这是功能丰富但工程成熟度不足的社区项目，值得作为“感知 Minecraft 环境的 AI 助手”继续维护。不能把首页的功能清单等同于经过验证的能力。原版不建议直接用于有稳定性要求的正式服务器。

上游最后代码推送和最新 v1.2.0 发布均在 2025-07-01，距评估约 16 个月。仓库未归档，许可证为 MIT，可以保留原版权声明后修改、分发。小规模关注量和没有 issues 不足以证明稳定。本评估不包含恶意代码鉴定、完整依赖漏洞审计或负载认证。

## 同类对比

| 项目 | 文档中的重点 | 更适合的需求 | 能确定的维护情况 |
| --- | --- | --- | --- |
| MC-ai-chat-plugin | 环境感知、状态事件、上下文、网页管理 | AI 陪伴和生存助手 | 代码最后推送 2025-07-01；本次检查了源码和构建 |
| GPTforMC | 自定义 API、多模型、多身份、聊天触发词 | 简单可配置的多角色聊天 | 最后推送 2025-06-02；功能依据 README |
| DeepSeekPlugin | 上下文、Vault 收费、TrChat 对接 | 现有服务器的聊天和经济体系 | 代码最后推送 2026-04-27，最新 release v1.9.2 发布 2025-11-22；功能依据 README |

后两者未做同深度代码审计和实服测试，因此不作稳定性排名。DeepSeekPlugin 有更近的代码活动，但更新时间也不能单独代表质量。对接官方 API 的聊天插件都不等同于能自主挖矿建造的机器人。

## 发现与处理

1. **1.21.11 API 不兼容。** 旧属性、药水效果常量以及成就显示接口不能直接编译。迁移到 Paper 1.21.11、Java 21，并使用 Paper TPS API 替代读取 `recentTps` 的反射。
2. **私聊回复泄露。** 原回复队列无论广播开关都发给所有在线玩家。现在保存请求玩家 UUID，并按广播配置选择接收者；回归测试覆盖两种情况。
3. **线程边界。** 原启动在异步线程构造 Bukkit 组件，聊天预处理读取玩家位置，环境采集在线程池读取世界、实体和方块。初始化与聊天环境采样改回主线程，HTTP 请求继续在工作线程；API 入口拒绝主线程调用。附近实体扫描保持有界。
4. **后台网络暴露。** 原 `new Server(port)` 忽略 `web.host`，配置写 localhost 仍监听通配地址。现连接器遵守配置，默认 `127.0.0.1`，限制 Jetty 线程数量。
5. **后台认证。** 原登录只判断整个请求体是否包含 token，状态接口免认证。现精确解析 JSON 的 token 字段，所有管理 API 都要求 Bearer token，登录体限制 4096 字节。添加实际 HTTP 登录、错误 token、超大请求及未授权访问测试。
6. **未完成的 WebSocket。** 上游类仅组装消息，没有实现会话推送。停止注册此占位端点，移除未使用 WebSocket 依赖；不再把它计为已实现能力。
7. **配置保护与密钥日志。** 原启动可能重建损坏配置，密钥格式修复会把完整密钥写入日志。现启动失败保留原文件，重载失败保留上次配置，使用 YAML 序列化保存密钥，移除配置请求体/配置值日志及手工重写密钥逻辑。
8. **资源与保存。** 关闭环境采集器、配置热重载线程；停止网络请求。历史写入使用单写线程，强制保存不再通过 sleep 猜测完成；关闭处理线程后做最终保存。
9. **请求与依赖。** 移除对话外层重复重试，禁用历史感知请求的简单 prompt 缓存，设置 HTTP 总超时。Jetty 11.0.17 升到 11.0.26，删除未使用 Jackson、org.json 和重复 Servlet API 依赖，统一产物版本。

## 当前验证边界

- 使用 Java 21 对 Paper `1.21.11-R0.1-SNAPSHOT` 编译，目标字节码 Java 21。
- `mvn clean verify` 执行 10 项测试，覆盖配置损坏保护/重载/密钥保存、私聊/广播路由、API 鉴权、真实 Jetty HTTP 服务登录和关闭。
- 打包检查：包含插件描述文件和网页资源；不包含 Bukkit/Paper API 或 JUnit/Mockito 测试类。
- **未进行完整 Paper 服务器启动、真实玩家联机、实际 DeepSeek API、长时间运行或多人压力测试。** HTTP 测试使用模拟插件实例，不能代替实服测试。
- 本地 Windows Java 21 的默认 Unix socket 临时目录存在连接问题。验证时给测试 JVM 指定短目录：`mvn "-DargLine=-Djdk.net.unixdomain.tmpdir=C:/tmp" clean verify`。正常环境可直接 `mvn clean verify`。这个参数是本机测试环境设置，不是插件功能要求。

## 使用与迁移

这是预览版，不是稳定版。目标是 **Paper 1.21.11 / Java 21+**；不声明 Spigot、旧版服务器或 Folia 兼容。

1. 备份旧 `plugins/AIChatPlugin/` 数据目录和旧 JAR，在独立测试服操作。
2. 移除旧版本 JAR，安装新 JAR，完整重启。不要用 `/reload` 或 PlugMan 热替换。
3. 原 `config.yml`、`prompts.yml` 与历史数据路径保留；不强制覆盖已有配置。新安装默认聊天前缀改为 `!`，已有空前缀仍保留，可手动调整。
4. 配置 `settings.api-key`、`settings.api-base-url`（完整 chat/completions 地址）及 `settings.model`。无需把密钥发给维护者。
5. 默认后台只能从服务器本机访问。远程需要显式修改 `web.host` 或配置反向代理；每次启动的访问令牌从控制台获取。
6. 检查启动日志、`/aichat status`、`!你好` 或 `/ai 你好`，并用两个玩家验证私聊/广播、权限、事件响应、配置重载、停服后重开历史。

## 接下来优先做什么

第一阶段：真实 1.21.11 实服验收，修复发现的问题，补上模拟 AI 服务集成测试和多人并发测试。

第二阶段：把同一玩家的请求串行化，设置全局请求/排队预算并明确提示过载；统一命令和聊天的限流实现；整理按角色发送的历史消息，消除提示词中重复历史，并落实上下文 token/消息上限。现有源码包含多层缓存、重复执行器与配置读取路径，这次只修复明确问题，没有宣称全部重构完毕。

第三阶段：把监控、自动优化和手动 GC 从核心聊天流程中解耦；配置改为不可变快照，网页操作只提交受控动作；进一步审计后台控制器线程边界、依赖隔离和升级 Jetty 的长期支持方案。当前 Jetty 补丁升级不等于完成依赖安全审计。

有证据的缺陷优先于新功能。暂不扩展 NPC 执行指令、AI 修改世界等功能。

## 来源

- https://github.com/zsbooody/MC-ai-chat-plugin
- https://github.com/zsbooody/MC-ai-chat-plugin/blob/21e1616/pom.xml
- https://github.com/zsbooody/MC-ai-chat-plugin/blob/21e1616/src/main/java/com/example/aichatplugin/ConversationManager.java
- https://github.com/zsbooody/MC-ai-chat-plugin/blob/21e1616/src/main/java/com/example/aichatplugin/EnvironmentCollector.java
- https://github.com/zsbooody/MC-ai-chat-plugin/blob/21e1616/src/main/java/com/example/aichatplugin/web/WebServer.java
- https://github.com/ENA-QWQ/GPTforMC
- https://github.com/xionglongztz/DeepSeekPlugin
