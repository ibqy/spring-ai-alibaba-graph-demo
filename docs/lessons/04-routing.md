# 04 · 条件路由：让边选择路径

## 目标与前置

- 让同一张图根据 score 走 pass 或 revise，而不是在一个节点里伪装分支。
- 理解业务判断结果、路由标签、目标节点名三者的区别。
- 前置：能阅读顺序图与 ReplaceStrategy；了解布尔值。
- 实现类：`com.xb.graph.demo04.ConditionalGraphDemo`，级别 basic。
- 规则是本课定义的及格线 60，并非框架内置评分能力。

## 流程图

```text
START --> evaluate -- yes --> pass ----> END
                  \-- no --> revise --> END
```

evaluate 负责验证分数并写 passed。
条件边读取 passed，返回 yes 或 no 标签。
映射表把 yes 指向 pass，把 no 指向 revise；未选择的节点不会执行。

## 输入与逐节点状态

默认输入：`{"score":80}`。

| 路径 | evaluate 后 | 分支节点后 |
| --- | --- | --- |
| score=80 | `score=80, passed=true` | `result="通过"` |
| score=60 | `score=60, passed=true` | `result="通过"` |
| score=59 | `score=59, passed=false` | `result="请修改"` |

score 作为原始输入保留，passed 与 result 采用覆盖策略。
条件边不额外写一个 result；结果由真正被调度的业务节点返回。
外层执行状态在两支正常结束时都为 COMPLETED。
业务不通过不等于图执行失败，应读取 `state.result` 区分。

## 核心 API 与设计原因

- `addConditionalEdges` 是条件分流 API，不是普通多条 addEdge。
- `edge_async` 将读取状态并返回标签的同步函数适配成条件边。
- `Map.of("yes", "pass", "no", "revise")` 让判断与节点命名解耦。
- 显式映射也能让图的结构可检查、可生成图示。
- 条件判断消费 evaluate 写入的 passed，避免不同分支重复定义及格线。
- 节点读取的是已合并状态，因此 evaluate 的返回 Map 先于选边生效。
- pass 与 revise 各自连接 END，保证两条路径都有出口。
- 同时给 evaluate 添加两条普通边表示分发，不表示 if/else。

## 真实源码

<<< ../../src/main/java/com/xb/graph/demo04/ConditionalGraphDemo.java

`BasicGraphTest.conditionalRoutesBoundaryToPassAndLowerScoreToRevise` 覆盖 60 与 59。
测试不仅检查结果，还检查 pass 与 revise 互斥出现。
这样能发现“结果正确但两个节点都执行了”的错误拓扑。

## 运行请求

```bash
curl http://127.0.0.1:8098/api/demos
curl http://127.0.0.1:8098/api/demos/demo04/graph
curl -X POST http://127.0.0.1:8098/api/demos/demo04/run \
  -H 'Content-Type: application/json' -d '{"input":{"score":80}}'
curl -X POST http://127.0.0.1:8098/api/demos/demo04/run \
  -H 'Content-Type: application/json' -d '{"input":{"score":59}}'
```

响应结构统一为 `{executionId,demoId,status,state,visitedNodes}`。
第一条 run 的业务访问顺序为 evaluate、pass；第二条为 evaluate、revise。
两次请求各自有 executionId，不会互相覆盖运行记录。
图接口返回的 Mermaid 应能看到两条候选分支，不代表每次都执行两条。

## 手工推导

1. 80 是有限数字且在 0–100 内，验证通过。
2. `80 >= 60` 得到 true，evaluate 返回 passed=true。
3. 条件边得到标签 yes，映射到 pass。
4. pass 写入 result=通过，然后结束。
5. 对 59 重复推导时，只有步骤 2–4 改为 false、no、revise。

## 异常与边界

- 0 与 100 是合法边界；负数、超过 100、NaN 与无穷大在节点验证中被拒绝。
- 直接 Graph API 支持范围内 Number；缺少 score 不会自动采用 80。
- HTTP 运行层按 Integer 类型的样例限制为整数，因此 HTTP 与直接图调用的输入范围有区别。
- 客户端不能传 passed 强行选路；本项目输入白名单只开放 score。
- 未映射的路由标签是拓扑/程序错误，不能当作隐含的 else 分支。
- 本课没有“改分重试”或人工审核；那些需要额外状态与边。

## 练习

1. 分别手算 0、60、100 的路径，补充边界断言。
2. 增加优秀分支前，先画出标签到节点的完整映射表。
3. 解释为什么不能用外层 status 判断业务是否及格。
4. 把阈值误改为 `> 60`，观察哪个现有测试能够捕获它。
