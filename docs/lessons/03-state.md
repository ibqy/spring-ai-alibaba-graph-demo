# 03 · 状态管理：Replace 与 Append

## 目标与前置

- 理解节点返回的是更新量，合并策略才决定最终状态。
- 对比状态字段覆盖与事件列表追加，而不是手写共享 List 模拟合并。
- 前置：完成顺序编排，理解 state.value 与节点返回 Map。
- 实现类：`com.xb.graph.demo03.StateMergeGraphDemo`，级别 basic。
- 本课使用 draft、publish 两个真实节点，不涉及消息队列或数据库。

## 流程图

```text
START --> draft ----------------> publish --> END
          status=draft            status=published
          events += draft:Graph   events += published:Graph
```

两个节点都更新 status 与 events。
同名字段是否丢弃旧值，由该字段注册的 KeyStrategy 决定。
不能从 Map 的 put 行为推断 Graph 的状态合并行为。

## 输入与逐节点状态

输入：`{"text":"Graph"}`，无需客户端提供 events 数组。

| 时刻 | status | events |
| --- | --- | --- |
| 输入 | 尚不存在 | 尚不存在 |
| draft 后 | `draft` | `["draft:Graph"]` |
| publish 后 | `published` | `["draft:Graph","published:Graph"]` |

text 在每一步都保留为 Graph。
publish 返回的 events **只有一项**：`["published:Graph"]`。
最终列表有两项，是 AppendStrategy 合并的结果，不是 publish 手工复制旧列表。
status 最终只有 published，不保留 draft 字符串。

## 核心 API 与设计原因

- `ReplaceStrategy` 适用于 text、status 这样的当前值字段。
- `AppendStrategy` 适用于本课 events 这样的事件集合。
- keyStrategyFactory 返回字段到策略的映射，状态更新由图引擎统一应用。
- 每个节点返回 `List.of(本次事件)`，清楚表达一个增量。
- 不要把 `state.data()` 整体回传给节点更新接口。
- 若整体回传包含旧 events，旧列表可能再次被追加，造成重复记录。
- 不要直接原地修改 state 内的 List；这种副作用绕过了显式更新协议。
- Append 表示合并，不是事务日志，也不是一次且仅一次的投递保证。
- 顺序图使本课两条事件顺序确定；并行合并不应依赖完成时序。

## 真实源码

<<< ../../src/main/java/com/xb/graph/demo03/StateMergeGraphDemo.java

`BasicGraphTest.replaceKeepsLatestStatusWhileAppendKeepsBothEvents` 检查两个阶段。
如果把 events 改成 ReplaceStrategy，第二阶段的双事件断言就会失败。
如果 publish 回传整个旧列表，精确列表断言也会捕获重复。

## 运行请求

```bash
curl http://127.0.0.1:8098/api/demos
curl http://127.0.0.1:8098/api/demos/demo03/graph
curl -X POST http://127.0.0.1:8098/api/demos/demo03/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"text":"Graph"}}'
```

返回形状为 `{executionId,demoId,status,state,visitedNodes}`。
注意外层 `status=COMPLETED` 是运行状态，内层 `state.status=published` 是业务状态。
`state.events` 包含两条字符串，业务节点访问顺序为 draft、publish。
统一接口只允许 sampleInput 声明的输入字段，不需要也不应在 HTTP 请求中构造 events。
这是本项目的输入白名单，不是 StateGraph 限制输入只能是标量。

## 手工推导

1. draft 返回 status=draft；旧值不存在，直接得到 draft。
2. draft 返回一条事件；列表从不存在变成一项。
3. publish 的 status 覆盖 draft，得到 published。
4. publish 的事件追加到原列表，得到两项，不是替换第一项。
5. text 始终没被节点覆盖，最终还是原始输入。

## 异常与边界

- 空白或非字符串 text 被 draft 拒绝。
- 本课不清洗 text；带空格输入会原样出现在事件内容中。
- AppendStrategy 不是去重协议，重复执行同一业务可能产生重复事件。
- 若复用检查点执行上下文，已有 events 也参与合并；第 09 课解释上下文隔离。
- 普通新执行的 HTTP run 由运行层创建独立会话，不会累积上一次请求的 events。
- 需要审计持久化时还需选择存储、保留策略与访问控制，本课并未实现这些能力。

## 练习

1. 先预测 events 改用 ReplaceStrategy 的结果，再用测试验证。
2. 给 publish 增加独立 `publishedText` 字段，应选哪种策略？
3. 如果需要按事件 ID 去重，应在业务规则还是 Append 名字上寄托保证？
4. 解释为什么返回增量比返回整个 state 更适合后续并行图。
