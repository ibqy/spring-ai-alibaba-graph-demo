# 从零启动：先不需要 API Key

## 环境要求

| 工具 | 要求 | 用途 |
| --- | --- | --- |
| JDK | 21 | 编译、运行 Java 示例 |
| Maven | 3.6.3 或更高 | 依赖管理与测试 |
| Node.js | 22 LTS 推荐 | 本地文档站，运行 Java 时不需要 |
| npm | 随 Node.js 安装 | 安装文档站依赖 |
| DashScope Key | 可选 | 只有真实模型模式需要 |

依赖锁定为 Graph / DashScope **1.1.2.2**、Spring AI **1.1.2**、Spring Boot **3.5.8**。首次构建需要网络下载依赖；“无 Key”不等于首次构建完全离线。

## 1. 获取代码并确认 Java

```bash
git clone https://github.com/ibqy/spring-ai-alibaba-graph-demo.git
cd spring-ai-alibaba-graph-demo
java -version
mvn -version
```

`mvn -version` 显示的 Java 也必须是 21。IDE 的项目 SDK 与终端使用的 Java 可以不同，不要只检查 IDE 设置。

Windows PowerShell 临时指定 JDK：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -version
```

Git Bash / macOS / Linux：

```bash
export JAVA_HOME="/path/to/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -version
```

将路径替换为真实 JDK 根目录，里面应当有 `bin/java` 或 `bin/java.exe`。

## 2. 跑测试，验证图而不是模型

```bash
mvn test
```

默认测试使用真实 Graph 和确定性本地数据，不需要 Key，不会调用付费模型。测试验证分支选择、循环退出、并行结果、人审前无副作用、审批恢复、会话隔离与重复请求。

只运行一个分组：

```bash
mvn -Dtest=HelloGraphTest test
mvn -Dtest=BasicGraphTest test
mvn -Dtest=AdvancedGraphTest test
mvn -Dtest=PracticeGraphTest test
mvn -Dtest=GraphRuntimeTest,GraphApiTest test
```

## 3. 启动教学服务

```bash
mvn spring-boot:run
```

默认监听 `127.0.0.1:8098`，仅供本机访问。模型模式为 `offline`，模型节点返回明确标注的教学模拟内容；路由、状态更新和检查点仍由真实 Graph 执行。

查看课程及其默认输入：

```bash
curl http://127.0.0.1:8098/api/demos
```

返回每个 Demo 的 `id`、标题、级别、`sampleInput` 和是否需要人工审核。**以这里的 sampleInput 为每课输入契约**，不必猜测字段名称。

## 4. 运行第一张图

```bash
curl -X POST http://127.0.0.1:8098/api/demos/demo01/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"name":"小北"}}'
```

关键结果：

```json
{
  "demoId": "demo01",
  "status": "COMPLETED",
  "state": {
    "name": "小北",
    "greeting": "你好，小北！欢迎学习 Graph。"
  }
}
```

实际响应还包含随机的 `executionId` 与真实 `visitedNodes`。这里省略它们只是为了突出断言，不表示接口没有这些字段。

PowerShell 请使用 `curl.exe`，避免旧版 PowerShell 将 `curl` 解释为其他命令。也可使用原生请求：

```powershell
$body = @{ input = @{ name = '小北' } } | ConvertTo-Json
Invoke-RestMethod -Uri 'http://127.0.0.1:8098/api/demos/demo01/run' `
  -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

## 5. 体验人工审批

先使用默认输入启动：

```bash
curl -X POST http://127.0.0.1:8098/api/demos/demo10/run \
  -H 'Content-Type: application/json' -d '{"input":{}}'
```

响应应为 `WAITING_REVIEW`。复制响应中的 executionId，再恢复：

```bash
curl -X POST http://127.0.0.1:8098/api/executions/替换为executionId/resume \
  -H 'Content-Type: application/json' -d '{"approved":true}'
```

改为 `false` 会进入拒绝分支；请创建一次新执行体验拒绝，已完成的执行不能重复审批。

## 6. 查看图与节点事件

```bash
curl http://127.0.0.1:8098/api/demos/demo06/graph
curl -N http://127.0.0.1:8098/api/demos/demo11/stream
```

第一个返回从实际图生成的 Mermaid 源码。第二个按节点发送 SSE JSON 事件，不是模型 token 流；`-N` 关闭 curl 的输出缓冲。

## 7. 可选：接入真实 DashScope

只有你明确启用真实模型时，模型节点才向外部发送输入，可能产生模型调用费用。不要输入真实个人信息或公司机密。

```bash
export DASHSCOPE_API_KEY="你的密钥"
export GRAPH_MODEL_MODE="dashscope"
mvn spring-boot:run
```

PowerShell：

```powershell
$env:DASHSCOPE_API_KEY = '你的密钥'
$env:GRAPH_MODEL_MODE = 'dashscope'
mvn spring-boot:run
```

不要把密钥写入 Git、前端配置或文档。完成后移除变量，或将 `GRAPH_MODEL_MODE` 设回 `offline`。真实模型输出存在随机性，默认测试中的固定文本断言不用于评价模型生成质量。

## 8. 本地阅读网站

另开终端：

```bash
npm ci
npm run docs:dev
```

访问终端打印的本地地址，注意项目配置了 `/spring-ai-alibaba-graph-demo/` 子路径。文档站与 Java 服务是独立进程，阅读文档不要求启动 Java。

发布前构建：

```bash
mvn verify
npm run docs:build
npm run docs:preview
```

GitHub Pages 只部署静态文档，不会部署 8098 上的 Java API。

## 接下来怎么学

- 没接触过图编排：按 [01 第一张图](/lessons/01-hello) 到 [06 并行](/lessons/06-parallel) 顺序学习。
- 已经会写条件路由：从 [07 子图](/lessons/07-subgraph) 开始，重点看检查点和人工审批。
- 希望了解业务一致性：先看 [14 幂等](/lessons/14-idempotency)，再读 [17 报销](/lessons/17-expense)。
- 遇到报错：查看 [排错指南](/guide/troubleshooting)；对外展示或改造成产品前必读 [实现边界](/guide/boundaries)。
