# 第 10 课：人工审批与检查点恢复

## 学习目标
- 把“等待人决定”表示成图的暂停位置，而不是线程睡眠或循环轮询。
- 看清暂停前已完成的准备工作与恢复后才执行的审批节点。
- 首次运行与恢复使用同一 executionId，通过和拒绝分支实验使用各自独立的执行。
- 区分执行状态 WAITING_REVIEW 与业务状态 PENDING。

## 图结构
```text
START -> prepare -> [暂停点] -> review
                                 |
                           approved 布尔值
                            /          \
                         true          false
                          |              |
                       approve         reject
                          |              |
                         END            END
```
暂停发生在 review 之前，所以第一次请求不会留下 review 的完成轨迹。
prepare 只产生待审批草稿，不执行发布或其他不可逆副作用。
approve 的“可发布”也是教学结果文本，本课不会真的发布文章。

## 输入与逐节点状态
| 阶段 | 读取 | 新增或覆盖 | trace 追加 |
| --- | --- | --- | --- |
| 输入 | request 非空字符串 | 原始申请材料 | 无 |
| prepare | request | draft、decision=PENDING | prepare |
| 暂停 | 检查点 | 运行器标记 WAITING_REVIEW | 无 |
| 恢复 | approved=true 或 false | 更新检查点中的 approved | 无 |
| review | approved | reviewed=true | review |
| approve | request | decision=APPROVED、result | approve |
| reject | 无 | decision=REJECTED、result | reject |

sampleInput 只有 request，不允许在首次运行时夹带 approved 绕过审核入口。
approved 必须是 JSON 布尔值，字符串 "false" 不是拒绝决定。
trace 使用 AppendStrategy，节点只提交本次的元素，不复制旧列表。
request、draft、decision、approved、reviewed、result 显式注册 ReplaceStrategy。
Graph 1.1.2.2 恢复时经 OverAllState.input 装载检查点，只装载已注册策略的键。
只注册 trace 会出现“检查点已有 approved，恢复节点却读不到”的错误，不能依赖普通执行的默认覆盖行为。

## API 为什么这样使用
`reviewNodes()` 明确返回 `Set.of("review")`，运行器据此配置 interruptBefore。
单独调用 `graph().compile()` 不会自动暂停，测试或其他入口必须传入中断配置。
运行器给图安装 MemorySaver，并用 executionId 关联独立 threadId。
MemorySaver 保存的是检查点，不是永久审批数据库。
`updateState(config, Map.of("approved", approved))` 返回新的 RunnableConfig。
恢复必须使用这个新配置的 `withResume()`，不能丢弃返回值。
`stream(null, updated.withResume())` 表示从已保存的状态继续，而不是重新输入申请。
条件边读取 approved，true 路由 approve，false 路由 reject。
`node_async` 与 `edge_async` 位于 action 包，只包装同步逻辑，不承诺自动切换线程。

## 实际请求
先启动项目，在 Git Bash 中保存首次响应，以下示例使用 jq 读取 JSON。
```bash
review=$(curl -sS -X POST http://127.0.0.1:8098/api/demos/demo10/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"request":"发布教学文章"}}')
executionId=$(jq -r '.executionId' <<< "$review")
curl -sS -X POST "http://127.0.0.1:8098/api/executions/$executionId/resume" \
  -H 'Content-Type: application/json' -d '{"approved":true}'
```
拒绝实验必须另建一次执行，不能在已通过的执行上再次审批。
```bash
review=$(curl -sS -X POST http://127.0.0.1:8098/api/demos/demo10/run \
  -H 'Content-Type: application/json' -d '{"input":{"request":"发布未校对文章"}}')
executionId=$(jq -r '.executionId' <<< "$review")
curl -sS -X POST "http://127.0.0.1:8098/api/executions/$executionId/resume" \
  -H 'Content-Type: application/json' -d '{"approved":false}'
curl -sS http://127.0.0.1:8098/api/demos/demo10/graph
curl -N http://127.0.0.1:8098/api/demos/demo10/stream
```
GET stream 使用 sampleInput，审批图的流会在等待人工审核时结束，不会无限保持等待。
GET graph 返回 Mermaid 图表示，图定义本身不等于已执行轨迹。

## 预期结果
统一运行响应包含 executionId、demoId、status、state、visitedNodes。
第一次响应 demoId=demo10，status=WAITING_REVIEW，实际节点轨迹 trace 为 `["prepare"]`。
此时 state.draft 为“待审批：发布教学文章”，state.decision=PENDING。
通过后 status=COMPLETED，state.decision=APPROVED，state.reviewed=true。
累计 trace 为 `["prepare","review","approve"]`，不会出现 reject。
拒绝后 status 同样为 COMPLETED，但 state.decision=REJECTED。
拒绝路径累计 trace 为 `["prepare","review","reject"]`，不会出现 approve。
正确过滤控制输出的 visitedNodes 应与上述 trace 一致。
Graph 的 InterruptionMetadata 也是 NodeOutput，并可能沿用 prepare 名称；它不表示 prepare 又执行一次。
若运行器未过滤此暂停标记，会观察到 `["prepare","prepare","review","reject"]`；需由运行器过滤标记，不能按节点名去重，否则会抹掉合法重试。
COMPLETED 说明流程走到了终点，不代表审批一定通过。

## 异常与边界
- request 为空或类型错误时拒绝输入，不生成有效草稿。
- 直接绕过运行器且不给 approved，review 抛出明确的参数异常。
- 运行器只允许等待审核的执行恢复，已结束执行不应重复审批。
- executionId 不存在、会话被移除或服务重启后，不能凭旧编号恢复。
- 本地 MemorySaver 没有跨进程持久化和审批权限校验能力。
- 真实系统还需要审核人身份、权限、审计日志、审批版本和并发控制。

## 源码与验证入口
- `src/main/java/com/xb/graph/demo10/HumanReviewDemo.java`：节点、条件边和 reviewNodes。
- `src/main/java/com/xb/graph/GraphDemo.java`：运行器与课程的公共约定。
- `src/main/java/com/xb/graph/web/GraphRuntime.java`：安装检查点、识别等待状态和恢复执行。
- `src/test/java/com/xb/graph/AdvancedGraphTest.java`：真实暂停、正反审批、缺少布尔决定。
测试断言恢复后只经过选定分支，并核对完整 trace，不只检查最终文本。

## 练习
1. 先记录暂停前 state，再通过审批，逐字段指出恢复期间的变化。
2. 在两次独立执行中分别传 true 和 false，对比 visitedNodes。
3. 思考加入“修改后重新提交”时应新增哪个节点，以及怎样避免重复发布。
4. 设计审核人身份字段应由哪一层注入，为什么不能直接信任客户端任意输入。
