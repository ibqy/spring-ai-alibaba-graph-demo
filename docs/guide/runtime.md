# 运行与调试

统一入口让 18 课使用同一套 HTTP 协议，但每课的核心仍是独立的 `StateGraph`。先按[开始学习](./getting-started)启动本地服务，再用本页检查输入、状态与执行路径。

## 接口一览

所有地址以 `http://127.0.0.1:8098` 为前缀；网站上的文档路径不是后端接口。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/demos` | 按编号列出标题、级别、样例输入、是否包含审核节点 |
| GET | `/api/demos/{demoId}/graph` | 从真实图定义生成 Mermaid 文本 |
| POST | `/api/demos/{demoId}/run` | 新建一次执行，返回终态或待审核状态 |
| GET | `/api/demos/{demoId}/stream` | 使用默认输入新建执行，以 SSE 输出节点事件 |
| GET | `/api/executions/{executionId}` | 查询一次执行的结果快照 |
| GET | `/api/executions/{executionId}/history` | 查询该执行的检查点历史 |
| POST | `/api/executions/{executionId}/resume` | 为等待审核的执行提交批准或拒绝 |

`demoId` 是 `demo01` 至 `demo18`。`executionId` 是每次运行返回的 UUID，不能用课程编号代替。

## 查看可用输入

```bash
curl http://127.0.0.1:8098/api/demos
curl http://127.0.0.1:8098/api/demos/demo04/graph
```

目录中的 `sampleInput` 是输入白名单，也是字段类型的参照；省略字段时采用该课默认值。图接口只编译图定义，不执行节点，也不调用模型。

```bash
curl -X POST http://127.0.0.1:8098/api/demos/demo01/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"name":"小北"}}'
```

典型响应结构如下，UUID 每次不同；框架可能在 `state` 中加入内部字段，不应作为业务接口依赖。

```json
{
  "executionId": "替换为响应中的 UUID",
  "demoId": "demo01",
  "status": "COMPLETED",
  "state": {
    "name": "小北",
    "greeting": "你好，小北！欢迎学习 Graph。"
  },
  "visitedNodes": ["greet"]
}
```

请求必须有 `input` 对象；运行默认示例时发送 `{"input":{}}`。不能在初始输入中塞入 `approved` 绕过审核，也不能添加样例未声明的键。

### 输入类型与错误响应

- 文本须为 1–8000 字符的非空字符串。
- 整数字段拒绝小数、越界值和数字字符串；业务范围由课程节点继续校验。
- 审核须为 JSON 布尔值 `true` 或 `false`，不是字符串 `"true"`。
- 未知的请求顶层字段也会被拒绝，不会静默忽略。

| HTTP 状态 | 含义 | 处理方式 |
| --- | --- | --- |
| 400 | JSON、字段类型或业务输入无效 | 对照目录的 `sampleInput` 和错误消息修正 |
| 404 | 课程不存在，或执行记录不存在/已淘汰 | 核对编号；服务重启后需重新运行 |
| 409 | 执行不在待审核状态，或运行中读取历史 | 不要重复审批；暂停或结束后再读取历史 |
| 429 | 100 条记录全部运行中或等待审核 | 完成待审核流程；仅在教学环境中按需重启 |
| 500 | 图或模型执行失败 | 检查本地运行条件，不把内部异常直接暴露到公网 |

错误体统一包含 `message`，不会返回完整异常堆栈或模型密钥。

## 暂停、检查与恢复

以[人工审核](../lessons/10-human-review)为例，先使用默认输入创建执行。

```bash
curl -X POST http://127.0.0.1:8098/api/demos/demo10/run \
  -H 'Content-Type: application/json' -d '{"input":{}}'
```

此时应得到 `WAITING_REVIEW`。复制响应中的 `executionId`，替换以下占位值：

```bash
EXECUTION_ID='替换为实际 UUID'
curl "http://127.0.0.1:8098/api/executions/$EXECUTION_ID"
curl "http://127.0.0.1:8098/api/executions/$EXECUTION_ID/history"
curl -X POST "http://127.0.0.1:8098/api/executions/$EXECUTION_ID/resume" \
  -H 'Content-Type: application/json' -d '{"approved":true}'
```

拒绝时使用 `{"approved":false}`。对已经结束的同一执行再次提交，返回 409；如果想试拒绝分支，应重新创建一次执行。

运行器把 `executionId` 同时作为 Graph 的 `threadId`，持有原始 `CompiledGraph` 与 `MemorySaver`。每次恢复都按 threadId 选择最新检查点，调用 `updateState` 写入审核结论，再用 `withResume()` 和 `stream(null, config)` 继续，**不是重新运行整张图**。多处审核不能重复使用旧 checkpointId，否则会重放已经走过的节点。

流式执行处于 `RUNNING` 时，历史接口返回 409；请等到暂停或结束后查询，避免遍历正在写入的内存检查点集合。

::: warning 恢复字段必须声明策略
在本项目固定版本 1.1.2.2 中，检查点恢复通过 `OverAllState.input()` 装载数据，只恢复已声明策略的键。草稿、审核结论和业务 ID 都必须注册，详情见[核心概念](./concepts)。
:::

### 执行状态

| 状态 | 含义 |
| --- | --- |
| `RUNNING` | 正在执行，查询结果不是实时逐字段状态订阅 |
| `WAITING_REVIEW` | 在配置的审核节点之前暂停，副作用尚未执行 |
| `COMPLETED` | 图正常结束；业务成功、拒绝或补偿看 `state` 的业务字段 |
| `FAILED` | 执行异常，或耗尽递归上限而未到达顶层 END / 审核点 |
| `CANCELLED` | SSE 订阅被取消；不等于已撤销外部系统副作用 |

一次执行的审批由会话锁串行化，避免并发重复恢复。它是单进程教学保护，不是跨实例事务或分布式锁。

## 用 SSE 观察节点

```bash
curl -N http://127.0.0.1:8098/api/demos/demo11/stream
```

`-N` 关闭 curl 输出缓冲。响应为 `text/event-stream`，每条 `data:` 后是 JSON：

- `type=node`：包含 `executionId`、节点标识与当时状态；可能包括框架 START/END 标记。
- `type=result`：最终状态，可能是 `COMPLETED` 或 `WAITING_REVIEW`。
- `type=error`：流建立后执行失败，状态为 `FAILED`；这时不能再把 HTTP 状态改为 500。

这不是模型 token 流。默认节点很快，网络或客户端缓冲可能让多条事件一起出现；不能把肉眼观察到的间隔作为节点并发证据。

固定版本 Graph 1.1.2.2 的流转换在下游逐条消费时可能重排事件。运行器在 HTTP 边界设置 256 条有界 FIFO 缓冲，避免客户端背压传入该转换；缓冲溢出会失败，不静默丢弃节点。真实 HTTP 集成测试验证 `START → outline → draft → publish → END → result` 顺序。

浏览器 `EventSource` 默认自动重连，而本接口每次 GET 都创建新执行。因此收到 `result` 或 `error` 后必须调用 `close()`，异常断开时也应关闭，避免无意中重复运行：

```js
const events = new EventSource('http://127.0.0.1:8098/api/demos/demo11/stream')
events.onmessage = ({ data }) => {
  const event = JSON.parse(data)
  console.log(event)
  if (event.type === 'result' || event.type === 'error') events.close()
}
events.onerror = () => events.close()
```

该代码只适用于后端同源页面或本地调试；GitHub Pages 与本地服务不同源，本项目没有开放跨域访问。推荐直接使用 curl，不要为了在公开站点运行而放开服务监听地址与 CORS。

## 调试路径

1. 先读课程中的流程图，预测 `visitedNodes` 与输出。
2. 用 `/graph` 核对真实边连接；Mermaid 是文本，需要支持 Mermaid 的工具才能渲染。
3. 运行默认输入，再只改变一个条件，例如分数、重试失败次数或审批结论。
4. 查询 `/history` 对比每个检查点的 `node`、`next` 和状态，历史由框架返回，不额外承诺排序。
5. 在对应 Java 节点设置断点，重点观察“读状态 → 返回增量 → 按策略合并”。
6. 运行对应 JUnit 测试，区分业务边界失败与 Graph 配置错误。

```bash
mvn -B -ntp -Dtest=BasicGraphTest test
mvn -B -ntp -Dtest=AdvancedGraphTest test
mvn -B -ntp -Dtest=PracticeGraphTest test
mvn -B -ntp -Dtest=GraphRuntimeTest,GraphApiTest test
mvn -B -ntp verify
```

并行测试用门闩确认多个分支都能在汇合前进入，不靠 sleep 或执行时间猜测。幂等测试同时发起相同业务键的操作并检查模拟副作用次数。

## 运行范围

- 服务默认只监听 `127.0.0.1:8098`，没有用户认证，不应直接改为公网服务。
- 最多保留 100 条执行；满额时优先移除最早的非运行、非待审核记录。
- 使用 60 秒无事件超时保护，不是可中断任意阻塞代码的硬性总时长保证。
- 检查点、执行记录、模拟幂等账本都位于内存中，重启丢失；幂等账本没有生产级容量治理。
- DashScope 模式可能外发文本并产生费用；[模型接入](../lessons/12-model)说明如何显式启用。

实现入口是 `src/main/java/com/xb/graph/web/GraphRuntime.java` 和 `GraphController.java`。生产化前需要认证授权、持久化、超时取消、数据脱敏与外部业务事务设计，参见[能力与边界](./boundaries)。
