# 第 15 课：实际节点轨迹、耗时与失败观测

## 学习目标
- 在实际 NodeAction 调用边界采集耗时，而不是给演示界面填固定数字。
- 将节点执行轨迹、成功失败、失败类型和累计观测时间写入可检查的状态。
- 用明确的业务失败分支结束流程，避免把“图执行完毕”误解为“业务成功”。
- 认识本课没有接入监控平台、指标服务器或分布式链路后端。

## 图结构
```text
START -> prepare ----成功----> work ----成功----> report -> END
             |                 |
            失败              失败
             |                 |
             +-------> failure_report ------------------> END
```
每个业务节点都经过 observed 包装，因此正常节点与失败节点使用同一种记录结构。
prepare 校验输入并生成 normalizedTask。
work 在 fail=true 时真实抛出 IllegalStateException，包装器捕获并写入失败状态。
failure_report 生成业务失败结果，不重新执行 work，也不偷偷重试。

## 输入与逐节点状态
输入 task 是非空字符串，fail 是布尔值，默认 false。
| 节点 | 读取 | 业务增量 |
| --- | --- | --- |
| prepare | task、fail | normalizedTask |
| work | normalizedTask、fail | 成功时 result；失败时 error |
| report | 前面结果 | outcome=COMPLETED |
| failure_report | 失败路径 | outcome=FAILED、result=失败说明 |
每次包装器调用还写入 trace、observations、nodeFailed、failedNodes、observedElapsedNanos。
trace 是实际调用的节点名列表，不是预先根据 fail 拼出来的路径。
observations 中每个元素包含 node、status、elapsedNanos。
失败元素额外包含 errorType，例如 IllegalStateException。
nodeFailed 表示最近完成节点是否失败，不是整个执行的最终成败。
因此 failure_report 自己成功后，nodeFailed 可以变为 false，但 outcome 仍为 FAILED。
failedNodes 是本次执行已经观察到的失败节点数，不因后续报告节点成功而清零。

## 计时 API 的选择
包装器在 action.apply(state) 前后读取 System.nanoTime()。
nanoTime 适合测量同进程经过时间，不是可读时间戳，不能作为跨机器时钟。
elapsedNanos 是本次动作及包装器局部处理的测量值，不是固定等待时间。
observedElapsedNanos 累加这些观测值，不代表完整 HTTP 请求端到端延迟。
检查点存储、排队、传输、序列化和客户端渲染不会全部包含在其中。
低开销节点可能非常快；测试只验证非负，不写死大于某个毫秒阈值。
测试也不比较两次请求耗时是否相等，实际计时不应该是确定常量。

## 状态与失败处理的原因
trace 和 observations 分别配置 AppendStrategy，每个节点只追加自己的一条记录。
普通计数字段采用覆盖更新，包装器读取旧值后提交新值。
prepare 开始新执行时重置累计计时与失败计数。
包装器捕获 Exception，以便本课把失败作为状态交给条件边处理。
它不捕获 JVM Error，也不声称所有故障都能被正常转换成业务结果。
为了避免记录任意敏感消息，observations 只存失败类型，不存异常全文或输入材料。
error 字段使用固定教学说明，不把凭证或原始异常堆栈塞进响应。
`node_async` 仍只是同步动作包装，不自动创建独立线程或后台遥测任务。

## 实际请求：成功路径
```bash
curl -sS -X POST http://127.0.0.1:8098/api/demos/demo15/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"task":"检查教学订单","fail":false}}'
```
统一响应包含 executionId、demoId=demo15、status、state、visitedNodes。
预期 status=COMPLETED，state.outcome=COMPLETED，state.failedNodes=0。
visitedNodes 与 state.trace 都为 `["prepare","work","report"]`。
observations 有三条，status 均为 SUCCEEDED，elapsedNanos 均为非负数。
state.result 为“已处理：检查教学订单”。
不提供固定耗时样例，因为真实测量会随机器、调度与预热状态变化。

## 实际请求：失败路径与事件流
```bash
curl -sS -X POST http://127.0.0.1:8098/api/demos/demo15/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"task":"检查教学订单","fail":true}}'
curl -N http://127.0.0.1:8098/api/demos/demo15/stream
curl -sS http://127.0.0.1:8098/api/demos/demo15/graph
```
失败请求仍走到了 END，因此外层 status=COMPLETED，但 state.outcome=FAILED。
state.failedNodes=1，visitedNodes 为 `["prepare","work","failure_report"]`。
work 的观测 status=FAILED、errorType=IllegalStateException。
failure_report 的观测 status=SUCCEEDED，表示失败报告本身成功生成。
不能只看最后一条观测就断定所有业务节点成功。
GET stream 使用 sampleInput 的 fail=false，不会复用上一条失败请求。
GET graph 返回静态 Mermaid，耗时和失败信息必须从执行状态读取。

## 异常与观测盲区
- task 为空、fail 类型错误会被公共运行入口拒绝。
- 直接调用图时 prepare 也会校验，失败后记录并进入 failure_report，不执行 work。
- 进程崩溃时来不及提交状态的节点，可能没有完成观测记录。
- 包装器之外的调度、检查点或传输异常不保证出现在 observations 列表中。
- 运行器报告 FAILED 与本课业务 outcome=FAILED 是不同层次，需要一起分析。
- 本课不注册 Prometheus 指标、不推送 OpenTelemetry trace、不声称已有监控后台。
- MemorySaver 的内存历史在重启后丢失；教学状态不能代替生产审计日志。
- 生产遥测还需采样、脱敏、低基数标签、上下文传播以及长期存储。

## 源码与测试入口
- `src/main/java/com/xb/graph/demo15/ObservabilityDemo.java`：observed 包装器、真实计时与失败条件边。
- `src/main/java/com/xb/graph/web/GraphRuntime.java`：外层执行状态与 visitedNodes 的来源。
- `src/test/java/com/xb/graph/AdvancedGraphTest.java`：成功和失败轨迹、非负耗时及 errorType。
测试从真实 NodeOutput 推导访问顺序，再对比 observations，不使用合成监控数据。
所有失败均由本地确定性开关产生，不调用网络故障注入服务。

## 练习
1. 比较三条 elapsedNanos 的和与 observedElapsedNanos，解释它和浏览器耗时的差别。
2. fail=true 时同时读取 outcome、failedNodes、nodeFailed，说明三个值为何不同。
3. 描述业务成功但 SSE 连接断开时，业务状态和传输状态应怎样分别展示。
4. 设计真实监控后端的导出边界，避免给每个 executionId 创建高基数指标标签。
