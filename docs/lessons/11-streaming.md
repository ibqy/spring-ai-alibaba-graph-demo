# 第 11 课：节点事件流与阶段产物

## 学习目标
- 用同一个 StateGraph 观察完整运行结果与逐节点输出之间的区别。
- 让每个节点产生可消费的中间产物，而不是只在最后返回一句话。
- 正确区分 Graph 节点事件流与模型 token 流。
- 理解 SSE 是传输方式，事件粒度由实际生产者决定。

## 图结构
```text
START
  |
outline  -- OUTLINE：大纲
  |
draft    -- DRAFT：教学草稿
  |
publish  -- PUBLISHED：整理稿
  |
 END
```
三个节点顺序执行，后一个节点依赖前一个节点的真实输出。
publish 只整理文本，不连接文章发布平台。
图中没有大模型调用，也没有把字符串拆成字符伪装 token。

## 输入与逐节点状态
输入 topic 是非空字符串，默认值为“Graph 检查点”。
| 节点 | 读取状态 | 持久保留的产物 | 当前阶段字段 |
| --- | --- | --- | --- |
| outline | topic | outline | stage=OUTLINE、artifact=大纲 |
| draft | outline | draft | stage=DRAFT、artifact=草稿 |
| publish | draft | article | stage=PUBLISHED、artifact=整理稿 |
outline 生成“主题：概念 -> 状态 -> 实验”。
draft 把实际大纲嵌入教学草稿，增加“先观察节点状态，再对比执行结果”。
publish 把草稿整理为最终 article，并写入 streamKind=NODE_EVENTS。
artifact 是每阶段统一的展示字段，会被覆盖，而不是一直拼接。
outline、draft、article 使用不同键，因此最终状态仍可比较三份产物。
trace 按完成次序追加 outline、draft、publish。
这里追加的是节点名，不是文本块，也不是 token 使用量。

## API 为什么这样使用
`CompiledGraph.stream(input, config)` 返回 `Flux<NodeOutput>`。
NodeOutput 同时提供 node() 和 state()，节点名与输出状态必须一起解释。
运行器负责把事件转成 SSE，图类不负责拼接 `data:` 协议文本。
流中可能包含 START、END 控制输出，不能把每条事件都当作业务产物。
业务节点完成时才可读取对应阶段的 artifact。
`node_async` 只把同步 NodeAction 包装成异步接口，不自动制造线程或并行。
运行器是否调度到工作线程属于执行层决策，不由方法名字保证。
本课不用 sleep 制造“流动感”，快速节点的多个事件可能紧接着到达。
HTTP、代理或浏览器可以缓冲字节，因此网络到达间隔不是节点执行耗时。
图仍由运行器安装 MemorySaver 和 threadId，事件输出不会取消状态管理。

## 实际请求：最终状态
```bash
curl -sS -X POST http://127.0.0.1:8098/api/demos/demo11/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"topic":"检查点"}}'
```
统一响应有 executionId、demoId、status、state、visitedNodes。
预期 demoId=demo11，status=COMPLETED。
visitedNodes 为 `["outline","draft","publish"]`。
state.stage=PUBLISHED，state.streamKind=NODE_EVENTS。
state.outline 为“检查点：概念 -> 状态 -> 实验”。
state.draft 与 state.article 都包含这个大纲，而不是重新读取一个硬编码主题。

## 实际请求：逐节点 SSE
```bash
curl -N http://127.0.0.1:8098/api/demos/demo11/stream
curl -sS http://127.0.0.1:8098/api/demos/demo11/graph
```
`curl -N` 关闭 curl 自身输出缓冲，便于观察到达顺序。
GET stream 固定使用 sampleInput，此处主题是“Graph 检查点”，不是上一条 POST 的“检查点”。
不要往 GET stream 追加 input 请求体并期望替换样例。
观察 SSE 数据里的 node 和 state.stage，依次为 outline/OUTLINE、draft/DRAFT、publish/PUBLISHED。
运行器还会发出最终结果事件，其 status 用于判断执行结束。
GET graph 返回 Mermaid，可对照事件验证实际执行顺序。
Mermaid 展示静态拓扑，不包含真实耗时，也不代表事件已经发生。

## 预期观察方法
只筛选三个业务节点时，应该恰有三份不同阶段产物。
OUTLINE 事件不能被描述成“模型刚生成了第一个 token”。
DRAFT 事件已经是整个 draft 节点完成后的输出。
PUBLISHED 事件包含完整 article，不会继续冒出逐字生成片段。
同一次流内 executionId 保持一致，重新连接会建立新的样例执行。
不要把新的连接误当成前一个连接自动续传。
测试在事件到达时提取 stage 和 artifact，避免只断言最后状态。

## 异常与边界
- topic 为空或不是字符串时，outline 拒绝执行。
- 前置节点失败后，不应该继续使用不存在的大纲生成草稿。
- 客户端断开或网络缓冲不能证明业务失败或业务回滚。
- 节点输出携带状态，真实业务应主动限制敏感字段和响应大小。
- 此实现不提供 SSE 事件持久化、Last-Event-ID 重放或跨进程订阅。
- 若将来接入模型 token 流，必须明确额外的事件类型，不能复用本课说法混淆粒度。

## 源码与测试
- `src/main/java/com/xb/graph/demo11/StreamingDemo.java`：三个阶段的实际状态增量。
- `src/main/java/com/xb/graph/web/GraphRuntime.java`：stream 的执行与事件封装。
- `src/test/java/com/xb/graph/AdvancedGraphTest.java`：验证节点顺序、阶段顺序和主题传递。
测试通过真实 Flux 收集业务事件，不使用模拟 SSE 服务器替代图执行。
运行测试不需要模型 Key，不访问网络服务。

## 练习
1. 用不同 topic 调用 POST run，验证所有产物都保留这个主题。
2. 对比最终状态中的三个产物，说明为什么 artifact 不适合使用 AppendStrategy。
3. 设计一个额外“校对”阶段，并写出它应读取和新增哪些状态键。
4. 描述生产前端应该如何区分节点事件、最终结果事件和传输错误。
