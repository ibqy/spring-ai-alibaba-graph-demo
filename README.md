# Spring AI Alibaba Graph 学习实战

18 个原生 `StateGraph` 示例，从第一张图到人工审批和业务闭环。每课提供可执行 Java 代码、自动化测试与详细中文学习文档。

**[项目网站](https://ibqy.github.io/spring-ai-alibaba-graph-demo/) · [快速开始](docs/guide/getting-started.md) · [核心概念](docs/guide/concepts.md) · [实现边界](docs/guide/boundaries.md)**

## 这个项目与普通 Spring AI 教程有什么不同

- 主线是 `StateGraph`、状态合并、条件边、并行、子图与检查点，不是重复 ChatClient 基础调用。
- 默认无 API Key 可运行：图引擎是真的，模型输出与外部业务数据明确标注为教学模拟。
- 可选 DashScope 模式接入真实 qwen-plus；密钥只通过环境变量配置。
- 实战包含审批拒绝、循环退出、重试耗尽和幂等冲突，不只有成功路径。
- 文档网站与 Java 程序独立；GitHub Pages 只托管文档。

## 技术版本

| 项目 | 锁定版本 |
| --- | --- |
| Java | 21 |
| Spring Boot | 3.5.8 |
| Spring AI | 1.1.2 |
| Spring AI Alibaba Graph / DashScope | 1.1.2.2 |
| 文档站 | VitePress |

这些是本项目的验证基线，不宣称是最新版。升级依赖后需要重新验证状态合并、并行与审批恢复。

## 五分钟启动

```bash
git clone https://github.com/ibqy/spring-ai-alibaba-graph-demo.git
cd spring-ai-alibaba-graph-demo
mvn -version
mvn test
mvn spring-boot:run
```

确认 Maven 使用 Java 21。服务默认监听本机 `127.0.0.1:8098`。

```bash
curl http://127.0.0.1:8098/api/demos
curl -X POST http://127.0.0.1:8098/api/demos/demo01/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"name":"小北"}}'
```

返回 `COMPLETED`，状态中包含 `greeting: 你好，小北！欢迎学习 Graph。`。Windows PowerShell 命令见[快速开始](docs/guide/getting-started.md)。

## 18 课学习路线

| 阶段 | Demo | 主题 | 学习文档 |
| --- | --- | --- | --- |
| 基础 | 01 | 第一张图 | [Hello Graph](docs/lessons/01-hello.md) |
| 基础 | 02 | 节点与顺序边 | [顺序处理](docs/lessons/02-sequence.md) |
| 基础 | 03 | 状态更新与合并策略 | [Replace / Append](docs/lessons/03-state.md) |
| 基础 | 04 | 条件路由 | [分支选择](docs/lessons/04-routing.md) |
| 基础 | 05 | 循环与退出 | [有界回路](docs/lessons/05-loop.md) |
| 基础 | 06 | 并行与聚合 | [Fan-out / Fan-in](docs/lessons/06-parallel.md) |
| 高阶 | 07 | 子图组合 | [子流程](docs/lessons/07-subgraph.md) |
| 高阶 | 08 | 检查点与历史 | [Checkpoint](docs/lessons/08-checkpoint.md) |
| 高阶 | 09 | 会话隔离 | [threadId](docs/lessons/09-isolation.md) |
| 高阶 | 10 | 人工中断与恢复 | [Human-in-the-loop](docs/lessons/10-human-review.md) |
| 高阶 | 11 | 节点事件流 | [SSE](docs/lessons/11-streaming.md) |
| 高阶 | 12 | 模型节点 | [离线与 DashScope](docs/lessons/12-model.md) |
| 高阶 | 13 | 失败重试与补偿 | [业务恢复](docs/lessons/13-retry.md) |
| 高阶 | 14 | 副作用幂等 | [重复与冲突](docs/lessons/14-idempotency.md) |
| 高阶 | 15 | 执行观测与测试 | [轨迹与耗时](docs/lessons/15-observability.md) |
| 实战 | 16 | 智能工单 | [检索 → 草稿 → 人审 → 结案](docs/lessons/16-ticket.md) |
| 实战 | 17 | 报销审批 | [抽取 → 并行校验 → 人审 → 模拟入账](docs/lessons/17-expense.md) |
| 实战 | 18 | 研究报告 | [并行分析 → 质检返工 → 审核 → 报告](docs/lessons/18-research.md) |

## 人工审批不是自动批准

```bash
curl -X POST http://127.0.0.1:8098/api/demos/demo10/run \
  -H 'Content-Type: application/json' -d '{"input":{}}'
```

取响应中的 `executionId`；`WAITING_REVIEW` 表示图确实停在审批节点之前：

```bash
curl -X POST http://127.0.0.1:8098/api/executions/替换为executionId/resume \
  -H 'Content-Type: application/json' -d '{"approved":true}'
```

使用 `false` 体验拒绝分支。已结束执行再次审批返回冲突，不重复触发副作用。

## 项目结构

```text
src/main/java/com/xb/graph/
  GraphDemo.java           统一课程元数据与图定义接口
  TextGenerator.java       模型边界：显式区分离线与真实调用
  demo01/ ... demo18/      每课独立图定义与必要业务组件
  web/                    目录、运行、节点流、检查点和审批入口
src/test/java/com/xb/graph/  图行为测试与 HTTP 集成测试
docs/
  guide/                  安装、概念、运行、排错与边界
  lessons/                18 篇源码对应课程
  .vitepress/             网站导航与主题
.github/workflows/        测试、网站构建与 Pages 发布
```

## 接入真实模型（可选）

```bash
export DASHSCOPE_API_KEY="你的密钥"
export GRAPH_MODEL_MODE="dashscope"
mvn spring-boot:run
```

默认 `offline` 不是故障降级，是真正不调用外部模型的教学模式。切换到 DashScope 后，模型节点会把提示词发送给模型提供商，并可能计费。

## 本地文档网站

```bash
npm ci
npm run docs:dev
```

按终端显示的地址访问。发布验证：

```bash
mvn verify
npm run docs:build
```

`main` 分支推送后，GitHub Actions 先运行 Java 测试和文档构建，通过后发布 Pages。仓库需将 Pages 的构建来源设为 GitHub Actions。

## 使用边界

本项目是本机教学程序，不是可以直接暴露公网的审批系统。MemorySaver 和模拟业务记录不跨进程持久化；没有登录、审批授权、租户管理或真实资金操作。详情见[实现边界](docs/guide/boundaries.md)。

MIT License · 作者 ibqy
