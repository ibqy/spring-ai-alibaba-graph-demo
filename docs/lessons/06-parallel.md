# 06 · 并行分支：真正的 fan-out / fan-in

## 目标与前置

- 让三个独立节点并行读取同一份规范化文本，再汇聚完整结果。
- 区分“结果都有了”与“确实并发派发了”这两个测试目标。
- 前置：理解第 03 课 AppendStrategy，知道线程完成顺序不确定。
- 实现类：`com.xb.graph.demo06.ParallelGraphDemo`，级别 basic。
- 本课只做本地计算，教学重点是图拓扑，不是并行性能收益。

## 流程图

```text
                    +--> characters --+
START --> prepare --+--> words -------+--> aggregate --> END
                    +--> uppercase ---+
                       fan-out           fan-in
```

三个分支都只依赖 prepare，不读取其他分支结果。
aggregate 在所有分支完成并合并后才执行。
这里不是在一个节点里顺序调用三个函数，也不是条件分支三选一。

## 输入与逐节点状态

默认输入：`{"text":"Spring AI Graph"}`。

| 节点 | 返回的局部更新 |
| --- | --- |
| prepare | `normalized="Spring AI Graph"` |
| characters | `characters=15, parts=["characters=15"]` |
| words | `words=3, parts=["words=3"]` |
| uppercase | `uppercase="SPRING AI GRAPH", parts=["uppercase=SPRING AI GRAPH"]` |
| aggregate | `result="characters=15 | uppercase=SPRING AI GRAPH | words=3"` |

分支更新表不是时间顺序表，不能据此推断哪个分支先完成。
汇聚状态保留 text、normalized、三个独立结果、完整 parts 和 result。
parts 应恰好有三项；result 排序后拼接，消除结果显示对完成顺序的依赖。

## 核心 API 与设计原因

- `addEdge("prepare", List.of(...))` 描述一对多分发。
- `addEdge(List.of(...), "aggregate")` 描述多分支共同汇聚目标。
- Graph 1.1.2.2 的并行节点实现使用 Executor 调度分支并等待聚合完成。
- 本课不在节点里维护线程池，调度由图执行层承担。
- characters、words、uppercase 各写不同字段，避免共享标量的覆盖冲突。
- 三个分支共写 parts，因此为 parts 注册 AppendStrategy。
- 如果 parts 使用 ReplaceStrategy，不能依赖它保留所有分支结果。
- 分支只返回本次增量，不回传整个输入状态，避免重复追加已有列表。
- aggregate 对 parts 排序只是展示稳定性，不是在串行化执行分支。
- `node_async` 是接口适配；真正并行来自多出边拓扑与并行执行器。

## 真实源码

<<< ../../src/main/java/com/xb/graph/demo06/ParallelGraphDemo.java

`BasicGraphTest.parallelDispatchesAllThreeBranchesBeforeJoiningAndMergesEveryResult` 是并行证据。
它通过 `RunnableConfig.defaultParallelExecutor` 注入三线程执行器。
每个任务到达门闩后等待三个任务全部进入，再执行分支动作。
若没有真实分发，门闩无法打开；测试有超时，不会无限等待。
测试还断言 aggregate 只执行一次、三个字段齐全、parts 没有遗漏或重复。

## 运行请求

```bash
curl http://127.0.0.1:8098/api/demos
curl http://127.0.0.1:8098/api/demos/demo06/graph
curl -X POST http://127.0.0.1:8098/api/demos/demo06/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"text":"Spring AI Graph"}}'
```

响应为 `{executionId,demoId,status,state,visitedNodes}`，正常 status 为 COMPLETED。
重点核对 state 中的三个字段及 result，不用 visitedNodes 的排列证明并发。
运行层记录的是 Graph 发出的节点输出；并行图可能包含框架合成的节点名称。
图示用于理解拓扑，不是精确的线程时间线。

## 手工推导

1. Spring 是 6 个 UTF-16 单元，AI 是 2 个，Graph 是 5 个，加两个空格共 15。
2. 按规范化后的空格拆分得到 Spring、AI、Graph，共 3 项。
3. Locale.ROOT 转大写得到 SPRING AI GRAPH。
4. parts 收集三条增量，按字典序排序后得到 characters、uppercase、words 的展示顺序。

## 异常与边界

- 空白或非字符串输入在 prepare 阶段被拒绝，分支不会启动。
- characters 使用 Java String.length，统计 UTF-16 单元，不是用户感知字符数量。
- words 只是按空格分隔，不是中文分词或自然语言词数统计。
- 并行分支异常会使执行失败，本课没有容错汇聚、部分成功或自动补偿。
- 三段短字符串计算的并行调度成本可能大于收益，不能据此宣称吞吐提升。
- 若在分支访问外部系统，还需要限流、超时和幂等设计，本课并未提供。

## 练习

1. 给输入添加前后空格，手算 prepare 后三个分支的结果。
2. 增加第四个独立统计分支，先扩展汇聚与门闩测试。
3. 思考两个分支都用 Replace 写 result 时为何不能得到可靠的联合结果。
4. 将需要另一个分支输出的操作移到 aggregate 后，画出新的数据依赖图。
