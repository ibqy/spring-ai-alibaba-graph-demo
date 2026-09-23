# 08 · 检查点：观察分步状态与历史

## 目标与前置

- 用 MemorySaver 保存真实图执行的检查点，而不是手工向数组追加“历史”。
- 读取某一步快照、当前状态，以及指定 checkpointId 的历史状态。
- 前置：理解第 02 课 stream 与 invoke 的区别，了解第 03 课合并策略。
- 实现类：`com.xb.graph.demo08.CheckpointGraphDemo`，级别 advanced。
- 本课没有人工中断或恢复审批；它专注于执行过程留痕与状态读取。

## 流程图

```text
START --> normalize --> measure --> summarize --> END
  |          |            |            |
  +----------+------------+------------+--> MemorySaver
                同一个 threadId 的检查点
```

图定义只描述三个计算节点，不持有 Saver，也不自己分配 threadId。
统一 HTTP 运行层负责配置 MemorySaver 和执行 ID。
直接 Graph 测试显式完成同样的配置，证明能力不是文案或接口包装出来的。

## 输入与逐节点状态

输入：`{"text":" Graph "}`。

| 阶段 | 关键 state 字段 | 尚未产生的字段 |
| --- | --- | --- |
| 输入 | `text=" Graph "` | normalized、length、summary |
| normalize | `normalized="Graph"` | length、summary |
| measure | `length=5` | summary |
| summarize | `summary="Graph (5)"` | 无 |

后续节点保留前面已有字段，最后 text 仍然是原始输入。
读取 normalize 的检查点时应看不到 length 与 summary。
如果所有“历史”都指向最终状态，就不能证明分步保存正确。

## 核心 API 与设计原因

- `CompileConfig` 是编译层配置，`SaverConfig` 注册 `MemorySaver`。
- `RunnableConfig.builder().threadId(...)` 指定本次运行所属上下文。
- `releaseThread(false)` 在直接测试中显式保留执行后的检查点供查询。
- `stream(input, config)` 展示节点输出，方便观察执行过程。
- `getStateHistory(config)` 读取 Saver 保存的 StateSnapshot 集合。
- `getState(config)` 读取所选上下文的状态；带 checkpointId 可定位历史快照。
- `snapshot.config()` 携带快照对应配置，不要自己猜测 checkpointId。
- 历史集合的排列顺序不在本课断言之内，测试按节点名称定位所需快照。
- stream 事件与检查点不是同一个对象，观察流不等于自动开启历史保存。
- 不带 Saver 的普通 compile() 仍能计算结果，但不能据此获得历史查询能力。

## 真实图源码

<<< ../../src/main/java/com/xb/graph/demo08/CheckpointGraphDemo.java

## 可执行的直接 Graph API 证据

以下配置辅助方法位于 BasicGraphTest，不依赖 HTTP 控制器。

<<< ../../src/test/java/com/xb/graph/BasicGraphTest.java#checkpoint-config

下面的完整测试保留了分步字段、历史读取与新 Saver 为空的断言。

<<< ../../src/test/java/com/xb/graph/BasicGraphTest.java#checkpoint-history

该测试先完整执行，再回读 normalize 快照，证明历史没有被后续节点覆盖。
测试使用全新 MemorySaver 读取同一个 threadId 时，历史为空。
这说明 threadId 不是磁盘文件名，也不能跨 Saver 实例自动找回历史。

## 运行请求

```bash
curl http://127.0.0.1:8098/api/demos
curl http://127.0.0.1:8098/api/demos/demo08/graph
curl -X POST http://127.0.0.1:8098/api/demos/demo08/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"text":" Graph "}}'
mvn -B -ntp -Dtest=HelloGraphTest,BasicGraphTest test
```

run 的响应仍是 `{executionId,demoId,status,state,visitedNodes}`。
正常返回 `status=COMPLETED`，`state.summary="Graph (5)"`。
业务节点依次是 normalize、measure、summarize。
run 响应不会因为本课开启检查点就自动增加 history 字段；历史 API 行为以上方直接测试为准。
这里没有另造 `/demo08/history` 接口，也不要求把 Saver 塞进输入 JSON。

## 手工推导

1. normalize 从首尾空白中提取 Graph，共五个 UTF-16 单元。
2. measure 写 length=5，summary 此时还未出现。
3. summarize 拼出 `Graph (5)`，完成最后一次业务更新。
4. 当前状态包含所有字段，normalize 历史快照仍只包含当时已产生的字段。
5. 改用全新 Saver 后，即使 threadId 相同，也没有之前的历史。

## 异常与边界

- MemorySaver 只在当前 JVM 内存中保存检查点，进程退出或实例丢弃后不可恢复。
- 这不是跨进程持久化，更不包含数据库备份、恢复演练或保留周期管理。
- 本项目运行层创建、保存和回收执行会话；它的容量与过期规则不是 Graph 引擎固有保证。
- 快照可读不等于任意节点都能安全重放；外部副作用还需幂等与恢复设计。
- text 为空白时 normalize 拒绝执行，不会产生成功的完整 summary。
- length 使用 String.length，不代表中文语义长度或 Unicode 字素数。

## 练习

1. 在 measure 与 summarize 之间增加一个节点，预测历史中新增字段的出现时刻。
2. 用 normalize 快照的配置再次 getState，确认不会得到最终 summary。
3. 去掉测试里的 Saver 注册，观察历史能力为何不能仅靠 threadId 获得。
4. 思考如何区分“图执行完成”“历史仍可查询”“数据已持久化”三种承诺。
