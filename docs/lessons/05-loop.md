# 05 · 循环控制：目标与最大轮数双重出口

## 目标与前置

- 用条件回边执行重复改进，而不是在一个节点内部写完整 while 循环。
- 同时提供目标达成和轮数耗尽两种正常业务出口。
- 前置：理解第 04 课的条件标签与映射。
- 实现类：`com.xb.graph.demo05.LoopGraphDemo`，级别 basic。
- 每轮加 2 分是确定性的教学规则，不是模型质量评估。

## 流程图

```text
START --> initialize -- stop ----------------> finish --> END
                \-- continue --> improve -- stop --^
                                     |
                         continue <--+  (回到 improve)
```

initialize 把 score 与 rounds 重置为 0。
initialize 与 improve 都使用 next 判断，不必先跑一轮才能检查出口。
next 只选边；finish 才写 reason，明确区分调度控制与业务输出。

## 输入与逐节点状态

默认输入：`{"target":6,"maxRounds":5}`。

| 节点/轮次 | score | rounds | 下一步 |
| --- | --- | --- | --- |
| initialize | 0 | 0 | improve |
| improve 第 1 次 | 2 | 1 | improve |
| improve 第 2 次 | 4 | 2 | improve |
| improve 第 3 次 | 6 | 3 | finish |
| finish | 6 | 3 | `reason=TARGET_REACHED` 后 END |

输入 target 与 maxRounds 也保留在最终 state。
另一个例子 `target=100,maxRounds=2` 得到 score=4、rounds=2、reason=MAX_ROUNDS。
若目标为 0，initialize 后立即到 finish，rounds=0。
若目标和上限同时满足，本课优先报告 TARGET_REACHED。

## 核心 API 与设计原因

- `addConditionalEdges("improve", ...)` 的 continue 标签指回同一个 improve。
- 同一节点多次执行不需要重复 addNode，节点 ID 表示处理步骤而不是执行次数。
- score、rounds 使用 ReplaceStrategy；每轮返回计算后的新值。
- 不使用 AppendStrategy 来存计数，否则会把数值变成不合适的累积结构。
- 两个入口共用 next，防止初次判断和回边判断的业务规则分叉。
- `target >= 0`，`maxRounds` 限制为 1–20 的整数，明确限制输入成本。
- 本课只会累加到 40，避免 score 的整数溢出问题。
- Graph 自身的 recursionLimit 是异常保护，不应代替正常的业务结束规则。
- 统一运行层的执行上限与本课 maxRounds 是不同层次的限制。

## 真实源码

<<< ../../src/main/java/com/xb/graph/demo05/LoopGraphDemo.java

`BasicGraphTest.loopStopsWhenTargetIsReached` 验证 3 轮达标。
`loopStopsAtBusinessLimitRatherThanGraphRecursionLimit` 验证 2 轮上限、0 轮目标与 20 轮边界。
上限测试还检查 improve 节点实际出现次数，不能只伪造 rounds 字段。

## 运行请求

```bash
curl http://127.0.0.1:8098/api/demos
curl http://127.0.0.1:8098/api/demos/demo05/graph
curl -X POST http://127.0.0.1:8098/api/demos/demo05/run \
  -H 'Content-Type: application/json' -d '{"input":{"target":6,"maxRounds":5}}'
curl -X POST http://127.0.0.1:8098/api/demos/demo05/run \
  -H 'Content-Type: application/json' -d '{"input":{"target":100,"maxRounds":2}}'
```

统一返回 `{executionId,demoId,status,state,visitedNodes}`。
两种正常退出外层都是 COMPLETED，差异在 `state.reason`。
默认路径中业务节点顺序为 initialize、improve、improve、improve、finish。
visitedNodes 中重复出现同一个名字是循环的正常现象，不应去重后再判断轮数。

## 手工推导

1. 目标 6，初始分数 0，每轮加 2，因此需要 3 轮。
2. 允许最多 5 轮，3 小于 5，所以先满足目标条件。
3. 目标 100、上限 2 时，2 轮只得到 4 分，无法达到目标。
4. 但 rounds 已等于 maxRounds，仍正常离开循环并记录上限原因。

## 异常与边界

- maxRounds=0、负数、1.5 或超过 20 会被拒绝，而不是截断后执行。
- target 必须在 int 非负范围内；缺少字段的直接 Graph 调用会失败。
- 统一 HTTP 运行层可补齐样例缺省字段，这是项目行为，不是图的默认参数。
- 若将 maxRounds 改得很大，应同时评估递归步数、超时与资源成本。
- 本课结束不表示目标总会实现；调用方必须检查 reason。
- Graph 的一步不一定等于业务的一轮，initialize、finish 也会消耗执行步骤。

## 练习

1. 手算 target=5,maxRounds=5，为什么 score 最终可能大于 target？
2. 手算 target=4,maxRounds=2，确认两个条件同时满足时的原因。
3. 若每轮提升由输入控制，应如何验证提升量为正以避免无进展循环？
4. 先补一个测试，再改进 finish 输出，让它提供剩余差距。
