# 09 · 执行隔离：同一张图，不同会话

## 目标与前置

- 在同一个 CompiledGraph、同一个 MemorySaver 中验证不同 threadId 不串状态。
- 同时验证相同 threadId 可延续已有状态，而不是每次都偷偷新建图。
- 前置：第 03 课 AppendStrategy、第 08 课 Saver 与 RunnableConfig。
- 实现类：`com.xb.graph.demo09.IsolationGraphDemo`，级别 advanced。
- 本课的 reply 是本地模板，不是大模型聊天，也没有用户登录系统。

## 流程图

```text
                     同一个 CompiledGraph
threadId=alice --> START --> record --> reply --> END
threadId=bob   --> START --> record --> reply --> END
                       |
              同一个 MemorySaver
                 |             |
              alice 历史      bob 历史
```

图结构与代码可以共用；消息历史必须按运行上下文区分。
threadId 是 Graph 执行上下文标识，不是 JVM Thread 的线程名。
本课没有用静态 Map 或 Spring 单例成员存储业务消息。

## 输入与逐节点状态

默认输入：`{"name":"小北","message":"你好"}`。

| 执行 | record 后 messages | reply 后 |
| --- | --- | --- |
| alice 第 1 次 | `["小北:你好"]` | `turns=1, reply="小北，你的第1条消息：你好"` |
| bob 第 1 次 | `["小南:独立会话"]` | `turns=1`，回复只引用小南的消息 |
| alice 第 2 次 | `["小北:你好","小北:继续"]` | `turns=2, reply="小北，你的第2条消息：继续"` |

alice 第二次执行后，读取 bob 最新状态仍只有小南的一条消息。
record 只追加当前消息，不重新追加历史；reply 读取合并后的列表计算 turns。
name 与 message 是本次最新输入，messages 才是累计消息集合。

## 核心 API 与设计原因

- messages 使用 AppendStrategy，name、message、turns、reply 使用 ReplaceStrategy。
- `RunnableConfig.threadId` 让 MemorySaver 区分检查点所属上下文。
- 复用同一 CompiledGraph 和 Saver 的测试，比各建一个图更能发现串会话问题。
- 同一个 threadId 的第二次 invoke 传入新输入，加载已有状态再执行图。
- 这与人工中断后的 resume 不是同一种操作，本课没有设置中断节点。
- 不应为了隔离而把图业务逻辑移到统一运行层；运行层只提供上下文配置。
- 单例 GraphDemo 是无会话状态的定义工厂，不应保存当前用户或上次回复。
- 相同 threadId 代表相同上下文；调用方错误复用 ID，就不能期待自动按 name 隔离。

## 真实图源码

<<< ../../src/main/java/com/xb/graph/demo09/IsolationGraphDemo.java

## 直接 Graph API 隔离证据

测试的 Saver 与 threadId 配置如下，明确保留检查点。

<<< ../../src/test/java/com/xb/graph/BasicGraphTest.java#checkpoint-config

测试按 alice、bob、alice 的顺序交错执行，并分别检查当前状态与所有历史快照。

<<< ../../src/test/java/com/xb/graph/BasicGraphTest.java#session-isolation

alice 历史不能出现 `小南:独立会话`，bob 历史不能出现小北的任意消息。
一个未使用过的 threadId 历史为空。
这些断言验证实际图与 Saver 行为，不是校验 JSON 文案或 Mock 返回值。

## 统一 HTTP 运行与直接 API 的区别

本项目每次 POST run 创建新的 executionId，并用它作为 threadId。
因此下面两个 HTTP 请求是两个独立执行，不会因为 name 相同就成为连续聊天。
运行层还为执行创建 MemorySaver；直接测试刻意共用 Saver，以验证更强的隔离场景。
本课没有公开“客户端指定 threadId 续聊”的输入字段，不要自行向 input 添加它。

```bash
curl http://127.0.0.1:8098/api/demos
curl http://127.0.0.1:8098/api/demos/demo09/graph
curl -X POST http://127.0.0.1:8098/api/demos/demo09/run \
  -H 'Content-Type: application/json' -d '{"input":{"name":"小北","message":"你好"}}'
curl -X POST http://127.0.0.1:8098/api/demos/demo09/run \
  -H 'Content-Type: application/json' -d '{"input":{"name":"小南","message":"独立会话"}}'
```

统一响应为 `{executionId,demoId,status,state,visitedNodes}`。
两次正常响应 status 都为 COMPLETED，业务访问节点为 record、reply。
两次 `state.turns` 都为 1；第一条 `state.reply` 是 `小北，你的第1条消息：你好`。
HTTP 返回的不同 executionId 是本项目分配规则；底层共享 Saver 隔离已由直接测试验证。

## 手工推导

1. alice 初次没有检查点，record 追加一条，reply 计数为 1。
2. bob 使用另一个 threadId，不能加载 alice 的 messages，所以也是 1。
3. alice 再次执行加载自己的旧列表，追加继续后变成 2。
4. bob 没有再执行，检查点仍只有独立会话这一条消息。
5. HTTP run 每次新建上下文，所以不能用第二次 POST 来复现 alice 的 turns=2。

## 异常与边界

- name 或 message 缺失、非字符串或空白时，直接图的 record 会拒绝执行。
- HTTP 可按 sampleInput 补缺省字段；这不是 Graph 自动生成用户消息。
- MemorySaver 不跨进程持久化，重启不能保留聊天历史。
- threadId 不是身份认证凭据；多租户服务还必须验证调用者对会话的访问权。
- 本课不承诺同一 threadId 的并发写入安全或顺序，需要调用层串行化或冲突控制。
- 不同 threadId 的状态隔离不等于线程、资源、权限或故障完全隔离。
- messages 持续追加会增长，真实服务还需历史截断、容量限制与隐私处理。

## 练习

1. 在直接测试中给 alice 与 bob 使用相同名字，说明为何仍应保持隔离。
2. 故意让两个配置使用相同 threadId，先预测 turns，再观察结果。
3. 如果开放续聊接口，列出必须增加的授权、过期、并发与输入校验规则。
4. 区分“新的 run”“同上下文新一轮 invoke”“中断后的 resume”三种操作。
