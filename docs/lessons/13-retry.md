# 第 13 课：真实条件回边、有限重试与补偿

## 学习目标
- 用图的条件回边驱动再次尝试，让重试路径能在节点轨迹中直接看到。
- 把可重试业务异常转成状态，避免异常直接中断图而无法走到补偿节点。
- 明确最多三次总尝试：首次一次，加上最多两次重试。
- 区分“重试耗尽后有补偿结果”和“业务调用成功”。

## 图结构
```text
START -> prepare -> attempt <--------+
                       |            |
                       +--失败且次数<3+
                       |
                       +--成功------> success ----> END
                       |
                       +--失败且次数=3-> compensate -> END
```
attempt 的 retry 路由真正回到 attempt 节点，不是在一个节点内部 for 循环。
因此每次尝试都会产生独立 NodeOutput，也会推进图检查点。
图中没有虚构的框架 RetryPolicy；重试上限是本课业务规则。
框架 recursionLimit 只是防止失控图循环的额外上限，不等于业务最大尝试次数。

## 输入与逐节点状态
failuresBeforeSuccess 是模拟调用在成功前应该失败多少次的非负整数。
它允许大于三，以演示不可能在本次预算内成功的情形。
| 节点 | 输入或条件 | 状态变化 |
| --- | --- | --- |
| prepare | 校验 failuresBeforeSuccess | attempts=0、succeeded=false、compensated=false |
| attempt | 上一次 attempts | attempts 加一，调用模拟业务方法 |
| attempt 失败 | 次数不超过预设失败数 | succeeded=false、lastError=暂时失败说明 |
| attempt 成功 | 次数超过预设失败数 | succeeded=true、lastError 清空 |
| success | 成功条件边 | outcome=SUCCEEDED、result=成功说明 |
| compensate | 第三次仍失败 | outcome=COMPENSATED、compensated=true |
prepare 总会重置内部计数，不信任外部传入的 attempts。
trace 记录如 attempt:1:failed，与 visitedNodes 中的 attempt 节点名用途不同。
lastError 清空说明最近一次已成功，不等于历史失败被抹掉；历史仍在 trace 中。
补偿节点只返回“模拟释放预留资源”，并未操作真实库存或支付系统。

## API 为什么这样使用
`addConditionalEdges("attempt", edge_async(...), routes)` 同时声明成功、重试、耗尽三种出口。
先判断 succeeded，再判断 attempts 上限，避免第三次成功却误走补偿。
捕获的只是自定义 TransientBusinessException，不把所有未知异常都当暂时失败。
模拟业务方法确实抛出异常，节点捕获后提交一个失败状态增量。
未经处理的编程错误仍应向上传播，不能无限重试掩盖缺陷。
`new StateGraph(() -> Map.of("trace", new AppendStrategy()))` 保留所有尝试轨迹。
每次节点提交当前尝试的一条记录，不重复提交整个历史。
`node_async` 和 `edge_async` 包装同步函数，本课没有后台重试线程或延迟任务。
运行器为图安装 MemorySaver 和 threadId，重试图仍遵守统一执行方式。

## 实际请求：第三次成功
```bash
curl -sS -X POST http://127.0.0.1:8098/api/demos/demo13/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"failuresBeforeSuccess":2}}'
```
预期 status=COMPLETED、state.attempts=3、state.outcome=SUCCEEDED。
state.compensated=false，state.lastError 是空字符串。
visitedNodes 为 `["prepare","attempt","attempt","attempt","success"]`。
trace 为 `["prepare","attempt:1:failed","attempt:2:failed","attempt:3:succeeded","success"]`。
统一响应还包含 executionId、demoId=demo13 与完整 state。

## 实际请求：耗尽补偿
```bash
curl -sS -X POST http://127.0.0.1:8098/api/demos/demo13/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"failuresBeforeSuccess":99}}'
curl -N http://127.0.0.1:8098/api/demos/demo13/stream
curl -sS http://127.0.0.1:8098/api/demos/demo13/graph
```
耗尽请求预期 attempts 仍为 3，而不是 99 或 100。
state.outcome=COMPENSATED、state.compensated=true，不出现第四次 attempt。
visitedNodes 为 `["prepare","attempt","attempt","attempt","compensate"]`。
status=COMPLETED 仅说明补偿分支走到了 END，不代表原业务成功。
GET stream 使用样例 failuresBeforeSuccess=2，会展示三次 attempt 后的 success。
GET graph 的 Mermaid 应包含 attempt 指向自身的回边。

## 边界实验
输入 failuresBeforeSuccess=0 时，一次调用就成功，attempts=1。
输入 1 时，第一次失败、第二次成功。
输入 3 时，三次都失败，因此与 99 一样进入补偿。
负数、小数、数字字符串、超出 int 范围的值均拒绝，不进行截断或默默转换。
示例非法请求如下：
```bash
curl -sS -X POST http://127.0.0.1:8098/api/demos/demo13/run \
  -H 'Content-Type: application/json' -d '{"input":{"failuresBeforeSuccess":-1}}'
```

## 生产边界与异常
- 本课重试目标是确定性的本地教学方法，没有网络调用或实际退避。
- 不应把 sleep 加到请求线程当作完整的生产退避方案。
- 生产系统需要定义哪些错误可重试，哪些错误必须立即终止。
- 对有副作用的操作重试前，必须先设计幂等键，下一课演示这一点。
- 补偿也可能失败；真正的补偿需要自己的状态、幂等和人工处理入口。
- 图的 MemorySaver 在进程重启后丢失，不保证重启后接着第三次尝试。

## 源码与测试证据入口
- `src/main/java/com/xb/graph/demo13/RetryDemo.java`：MAX_ATTEMPTS、模拟异常与条件回边。
- `src/test/java/com/xb/graph/AdvancedGraphTest.java`：成功、耗尽、零失败与非法计数。
测试断言真实 NodeOutput 中出现三次 attempt，而不只检查最终 attempts 数字。
补偿测试同时断言不存在第四次尝试以及只执行一次 compensate。

## 练习
1. 列出 failuresBeforeSuccess 为 0、1、2、3 时的完整 visitedNodes。
2. 解释把成功判断放在次数判断之后可能产生什么错误。
3. 为“补偿也失败”画出扩展图，避免将补偿成功当成必然前提。
4. 讨论恢复检查点时，外部业务调用已经成功但状态尚未保存会有什么问题。
