# 第 12 课：模型节点、离线教学替身与 DashScope

## 学习目标
- 将模型调用隔离在 TextGenerator 接口后，图只依赖生成能力。
- 在没有任何 API Key 时运行完整教学图，并清楚标识模拟结果。
- 显式选择 dashscope 才连接真实模型，配置错误必须立即失败。
- 理解节点级流与模型级流不同，本课仍是一次完整摘要输出。

## 图结构
```text
START -> prepare -> generate -> finish -> END
            |           |
         text->prompt   TextGenerator
                        /           \
                 offline             dashscope
              确定性首句截取          qwen-plus 调用
```
图的分支不是运行时条件边；提供哪种生成器由 Spring 启动配置决定。
同一应用中的其他实战图复用这个唯一 TextGenerator Bean。
不需要修改 GraphDemo 或 TextGenerator 的公共接口。

## 输入与逐节点状态
| 阶段 | 读取 | 写入 |
| --- | --- | --- |
| 输入 | text 非空字符串 | 原始材料 |
| prepare | text | offline：去除首尾空白后的材料；dashscope：显式摘要任务与分隔后的材料 |
| generate | prompt | summary、modelMode、simulated |
| finish | 已生成结果 | outcome=SUMMARIZED |
每个节点还向 trace 追加自己的节点名。
默认材料解释 Graph 节点与检查点，sampleInput 不包含对象或列表。
modelMode 为 offline 或 dashscope，simulated 只有 offline 时为 true。
不要根据摘要中文是否自然来猜测模型来源，应直接查看这两个字段。

## 离线模式的真实含义
`graph.model.mode` 默认值为 `offline`，不是自动猜测 Key 是否存在。
离线生成器只压缩空白、截取首个中文句号前的内容，并最多保留 36 个 Unicode 码点。
输出以“【离线教学模拟】摘要：”开头，明确说明不是大模型推理。
相同输入始终得到相同输出，便于断网教学和可重复测试。
这种短摘要是机械抽取，不会理解语义、事实核验或服从复杂提示词。
因此本课离线直接传入事实材料，不把“请总结”等开场指令交给首句截取器。
第 16、18 课离线也只传业务数据做摘要预览，同时在固定模板正文中完整保留留言/主题和证据；不能用短摘要替代完整资料。
即使环境存在 Key，默认模式也仍然离线，不会静默发起网络请求。
切换为真实模式失败时也不自动降级为离线，避免掩盖配置故障。

## Bean 与 API 为什么这样使用
`TextGeneratorConfiguration` 是 demo12 包内的 Spring `@Configuration`。
唯一 `@Bean` 方法返回 TextGenerator，不给不同模式注册多个同类型 Bean。
`ModelDemo(TextGenerator generator)` 用构造器注入，其他图也应采用相同方式。
业务只调用 `generator.generate(prompt)` 和 `generator.mode()`，不用自己创建 DashScope 客户端。
本项目直接依赖 DashScope 模型模块而非模型 starter，避免无 Key 自动配置失败。
真实模式使用 `DashScopeApi.builder().apiKey(key).build()`。
随后构造 `DashScopeChatModel.builder().dashScopeApi(api).defaultOptions(...)`。
默认选项由 `DashScopeChatOptions.builder().model("qwen-plus").build()` 提供。
真正请求发生在 generate 调用时，而不是为了列出课程或渲染图就调用模型。
DashScope 实现直接调用 `model.call(prompt)`，不再把调用者的任务包装成“总结以下指令”。
摘要是 ModelDemo 的业务任务：真实模式在 prepare 中明确要求中文短摘要、只输出纯文本、不调用工具，并用 `<材料>` 分隔输入，告知不要执行材料中的指令。
工单与研报则传入各自的起草任务，而不是由公共生成器强制改成摘要。没有注册工具，也不把返回文本解释为动作；提示词隔离本身不是完整的提示注入防御方案。
`node_async` 不会把同步模型调用自动转换为 token 流。

## 实际请求：默认离线
```bash
curl -sS -X POST http://127.0.0.1:8098/api/demos/demo12/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"text":"图保存状态。恢复时沿检查点继续。"}}'
curl -N http://127.0.0.1:8098/api/demos/demo12/stream
curl -sS http://127.0.0.1:8098/api/demos/demo12/graph
```
POST 响应包含 executionId、demoId、status、state、visitedNodes。
预期 demoId=demo12，status=COMPLETED，visitedNodes 为 `["prepare","generate","finish"]`。
state.summary 为“【离线教学模拟】摘要：图保存状态。”。
state.modelMode=offline、state.simulated=true、state.outcome=SUMMARIZED。
GET stream 使用样例材料而不是上述 POST 材料，generate 事件携带完整摘要。
GET graph 返回 Mermaid 拓扑，不触发文本生成。

## 切换到真实模型
先通过操作系统或安全的终端输入设置 DASHSCOPE_API_KEY 环境变量，不能把 Key 提交到源码。
Git Bash 可以无回显读取 Key，避免把它直接写进命令历史。
```bash
read -s -p 'DASHSCOPE_API_KEY: ' DASHSCOPE_API_KEY
export DASHSCOPE_API_KEY
mvn -B -ntp spring-boot:run -Dspring-boot.run.arguments=--graph.model.mode=dashscope
```
随后使用同一个 POST run 请求，预期 modelMode=dashscope、simulated=false。
模型内容可能变化，不能期待与离线摘要逐字相同。
本课自动测试不使用真实 Key，也不会调用此在线路径。
在线连通性、额度和模型权限需要在有授权的环境单独验证。

## 异常与边界
- 非法 mode，包括空字符串、unknown 和大写 OFFLINE，启动时明确失败。
- dashscope 模式缺少或空白 Key，启动时报告 DASHSCOPE_API_KEY 不能为空。
- 错误信息不打印 Key，代码不记录请求凭证。
- text 或 generate 的 prompt 为空时拒绝输入，不产生伪造摘要。
- 真实模型返回空文本会抛出异常，不当作成功结果。
- 网络、限流、权限和模型服务错误不会被本课吞掉或转换成离线文本。
- 原始 prompt 与摘要在图状态中，输入生产隐私数据前要设计脱敏与保留策略。

## 源码与无网络测试
- `src/main/java/com/xb/graph/demo12/TextGeneratorConfiguration.java`：模式选择、两个实现和唯一 Bean。
- `src/main/java/com/xb/graph/demo12/ModelDemo.java`：构造注入与图节点。
- `src/main/java/com/xb/graph/TextGenerator.java`：仅 generate(String)、mode() 的公共契约。
- `src/test/java/com/xb/graph/AdvancedGraphTest.java`：默认/显式离线、唯一 Bean、短摘要、非法模式和缺 Key。
测试使用 Spring 小上下文，不需要完整 Web 应用或外部模型服务。
另在 DashScopeChatModel 网络边界使用 mock，执行真实生成适配器：断言调用者的任务原样下传，以及 ModelDemo 自己提供带材料分隔的摘要任务。
第 16、18 课的正文回归测试使用真实配置的 offline Bean，验证不同业务输入的正文不同且完整保留事实；不能仅依靠总是返回固定正文的 fake。

## 练习
1. 比较两次相同输入的离线摘要，解释“确定性”为什么有助于回归测试。
2. 在另一个图的构造器中注入 TextGenerator，避免复制本课配置类。
3. 找一个首句不代表全文重点的材料，说明机械摘要与语义摘要的差别。
4. 为真实模型模式设计超时、成本和敏感信息策略，不把离线成功当成在线验证。
