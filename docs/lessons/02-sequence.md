# 02 · 顺序编排：清洗之后再处理

## 目标与前置

- 理解节点之间传递的是合并后的状态，而不是 Java 方法返回值的直接链式调用。
- 用 clean 与 process 两个节点实现清洗、转大写。
- 前置：完成第 01 课，能够运行测试并读取统一运行接口返回的 state。
- 本课属于 basic，使用本地字符串运算，不调用模型。
- 实现类：`com.xb.graph.demo02.SequenceGraphDemo`。

## 流程图

```text
START --> clean --> process --> END
          |           |
       cleaned      result
```

process 只能读到 clean 已经提交并合并的 cleaned。
这里没有并行；将两个 addNode 写在同一方法中并不自动形成先后关系。
真正的顺序来自三条 addEdge。

## 输入与逐节点状态

默认输入：`{"text":"  hello   Graph  "}`。

| 阶段 | 本节点新增字段 | 保留的上游字段 |
| --- | --- | --- |
| START | 无 | 原始 text |
| clean | `cleaned="hello Graph"` | text 不变 |
| process | `result="HELLO GRAPH"` | text、cleaned |
| END | 无 | 三个字段均保留 |

clean 使用 strip 去除首尾空白，再把匹配 `\s+` 的连续空白折叠为一个空格。
process 读取 cleaned，而不是重新读取原始 text。
因此测试必须包含多个空格，才能发现处理节点读错字段的错误。
单独用 `hello` 测试不足以证明清洗步骤真的参与了结果计算。

## 核心 API 与设计原因

- 三个字段显式使用 `ReplaceStrategy`，每个字段代表当前值而不是历史。
- clean 只返回 cleaned，保持原始 text 便于比对。
- process 只返回 result，避免不必要地重新合并已有字段。
- `Locale.ROOT` 让大写转换不依赖服务器默认语言环境。
- `state.<String>value("cleaned").orElseThrow()` 把缺少上游输出暴露为错误。
- 如果绕开 clean 直接进入 process，不应该静默使用空字符串掩盖错误。
- `stream(input)` 发出节点输出，适合观察中间状态。
- `invoke(input)` 适合只关心最终 state 的调用方。

## 真实源码

<<< ../../src/main/java/com/xb/graph/demo02/SequenceGraphDemo.java

对应测试是 `BasicGraphTest.sequenceCleansBeforeProcessingWithoutDestroyingInput`。
测试检查 clean 输出尚无 result，再检查 process 输出为 `HELLO GRAPH`。
这比仅断言请求成功更能捕获拓扑接错或字段读错的问题。

## 运行请求

```bash
curl http://127.0.0.1:8098/api/demos
curl http://127.0.0.1:8098/api/demos/demo02/graph
curl -X POST http://127.0.0.1:8098/api/demos/demo02/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"text":"  hello   Graph  "}}'
```

响应外层是 `{executionId,demoId,status,state,visitedNodes}`。
正常执行时 demoId 为 demo02，status 为 COMPLETED。
`state.cleaned` 为 `hello Graph`，`state.result` 为 `HELLO GRAPH`。
业务节点访问顺序是 `clean`、`process`，由运行层记录到 visitedNodes。
该列表不是节点自己写入 state 的字段。

## 手工推导

1. 删去前后两个空格，剩下 `hello   Graph`。
2. 折叠中间三个空格，得到 `hello Graph`，写入 cleaned。
3. 对 cleaned 做 Locale.ROOT 大写转换，得到 `HELLO GRAPH`。
4. 原始 text 仍有前后空格，这是保留原始输入的有意设计。

## 异常与边界

- 空文本、纯空白、非字符串会被 clean 拒绝。
- 运行层可补齐 sampleInput；直接调用图时缺失 text 不会自动使用样例。
- `\s+` 不是完整的所有语言空白规范，本课没有承诺 Unicode 文本归一化。
- 大写转换不是翻译；中文不会因此转换成英文。
- 若 process 抛异常，框架不会自动回滚已经完成的外部副作用；本课没有外部副作用。
- `stream` 中的节点状态与检查点历史不是同一概念，第 08 课才配置 Saver。

## 练习

1. 输入 `"  spring\tai  "`，推导 cleaned 与 result。
2. 将 process 错读为 text，观察现有测试为什么会失败，再还原。
3. 新增字数节点时，先决定它应读取 text 还是 cleaned，再画边。
4. 思考把 clean 和 process 并行执行为何会破坏依赖关系。
