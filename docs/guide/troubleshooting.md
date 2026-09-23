# 排错指南

## 按顺序缩小问题范围

先运行 `mvn -Dtest=HelloGraphTest test`，再启动离线应用，最后才启用 DashScope。这样可以区分工具链、图代码与外部服务问题。

| 现象 | 先检查 | 常见原因 |
| --- | --- | --- |
| `invalid target release: 21` | `mvn -version` | Maven 实际使用旧 JDK |
| `JAVA_HOME ... not defined correctly` | JDK 根目录下是否有 bin/java | 指向 JRE、空目录或错误层级 |
| 依赖下载失败 | Maven 日志里的具体 URL 与状态码 | 网络、镜像或代理问题 |
| 8098 启动失败 | 端口占用 | 已有服务运行 |
| 模型认证失败 | 模式和环境变量 | 开启 dashscope 但 Key 缺失或无权限 |
| 审批后没有继续 | executionId、Saver、withResume | 创建了新 Saver 或恢复了错误会话 |
| 状态列表重复 | 节点返回值 | Append 策略返回了完整历史 |
| 并行结果丢失 | 状态键与合并策略 | 两个分支替换同一个键 |
| SSE 看不到逐条输出 | curl -N | 客户端或代理缓冲 |
| Pages 404 | Actions、base、访问路径 | 构建失败、部署来源错误或路径错误 |

## Java 版本与编码

同一台电脑可以有多个 JDK。判断依据是构建命令输出，不是目录名字，也不是 IDE 截图。

```bash
java -version
mvn -version
```

Windows 下中文输出乱码不一定是编译失败。项目源码编码为 UTF-8，终端编码与 Maven JVM 输出编码也要一致。可以在当前终端临时设置：

```bash
export MAVEN_OPTS="-Dfile.encoding=UTF-8"
```

不要为了修复一个项目，直接覆盖全机 Java 环境。

## Maven 依赖下载

本项目通过 Maven Central 中的固定坐标解析依赖，不需要把版本换成 SNAPSHOT 来碰运气。

```bash
mvn -B -ntp dependency:resolve
mvn dependency:tree
```

先分辨 HTTP 401/403、连接超时、TLS 错误还是坐标不存在。不要关闭 TLS 校验，不要把公司镜像凭据写入 pom.xml。

本地依赖缓存存在并不意味着 CI 也有缓存；最终以干净环境的 Actions 验证为准。

## 离线启动却要求 Key

检查是否设置了 `GRAPH_MODEL_MODE=dashscope`，或传入了 `--graph.model.mode=dashscope`。

```bash
export GRAPH_MODEL_MODE=offline
mvn spring-boot:run
```

本项目使用直接模型模块，不靠无条件创建的 DashScope starter bean。离线模式不应尝试连接模型提供商。

## 端口冲突

可以给本次启动指定另一个端口，而不是结束未知进程：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8099"
```

随后把示例请求里的 8098 换成 8099。文档站的端口与 Java 端口互不相关。

## WAITING_REVIEW 不是错误

这是课程主动设计的中断点。先读响应中的草稿与校验结果，再发送审批请求。不要在初始输入里塞 `approved`，运行层会拒绝内部状态注入。

- 404：执行不存在、已被淘汰，或服务重启后内存记录消失。
- 409：执行不处于等待审批状态，常见于重复批准。
- 429：保留的 100 条运行全部未结束，应完成待审核流程或重启本地教学服务。

如果直接使用 Graph API，恢复顺序为：消费首次流 → 更新原检查点 → 使用返回的配置 `.withResume()` → 继续流。

## 并行与状态合并

不要用“线程日志先打印哪一行”判断图是否正确。检查汇聚节点是否拿到所有分支结果，以及是否使用了正确的合并策略。

- 需要保留两份结果：使用不同状态键或明确的追加策略。
- 使用 Append：节点只返回新增值。
- 顺序敏感的业务：显式排序或改用顺序边，不依赖调度偶然性。

## 循环结束与错误终止

业务循环应有自己的结束条件，例如质量达标或达到最大重试次数。框架的 recursionLimit 是最后一道执行上限，不应替代正常的业务出口。

把业务失败设计为状态与分支，可以在结果里看到补偿或拒绝原因；未处理异常会中止执行，不能把它当成一次成功完成。

## SSE 排查

```bash
curl -N http://127.0.0.1:8098/api/demos/demo11/stream
```

看每条 `data:` 中的 JSON，区分 `node`、`result` 和 `error`。服务发送结束事件后客户端应停止重连，否则浏览器 EventSource 默认重连可能重新创建一次示例运行。

本项目传递的是节点事件，不是模型每一个 token。使用反向代理时还要检查代理缓冲与空闲连接超时；本地教学默认不需要代理。

## 文档构建失败

```bash
npm ci
npm run docs:build
```

遇到死链或源码引用找不到，应修复对应路径，不能设置 ignoreDeadLinks 来隐藏问题。包含泛型的 Java 示例放在代码块中，避免被 Markdown/Vue 当成 HTML 标签。

## GitHub Pages 部署

检查仓库 Settings → Pages 的构建来源是否为 GitHub Actions，再看 `Verify and deploy learning site` 工作流。

工作流先测试 Java，再构建网站。前一步失败时不会部署，这时“已经推送”不等于“已经上线”。

项目站的 base 是 `/spring-ai-alibaba-graph-demo/`。浏览器刷新课程深层路径也应能访问，不能只检查首页。

## 排错时提供什么

提供命令、Java/Maven 版本、示例编号、去敏后的输入、HTTP 状态码与错误类型。不要提供 API Key、账号凭据、真实发票或公司资料。
