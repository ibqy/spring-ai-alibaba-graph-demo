# 第 18 课：研报并行分析、有界返工与人工模拟发布

## 1. 业务目标
- 将研报主题拆成“机会分析”和“风险分析”两个任务。
- 真正并行分析两份固定本地资料，并在汇合节点整合结果。
- 使用可控的 requiredRevisions 输入展示质量检查与回边。
- 最多允许两次整改，仍不合格就结束，避免无限循环。
- 达标后在 review 之前暂停，由人工决定是否生成最终报告。
- 批准后注入 TextGenerator 生成正文，拒绝则不生成正文。
- 所谓“发布”只是进程内状态中的模拟产物，不向网站、文件系统或其他服务发布。
- 本例没有联网检索，也不把本地演示资料伪装成最新市场研究。

## 2. ASCII 节点图
```text
START -> plan -> research <---------------------------+
                    |                                |
                    +--> analyze_market --+           |
                    |                     |           |
                    +--> analyze_risk ----+--> summarize
                                                |
                                              quality
                                                |
                       不合格且整改次数<2 -----> revise -+
                                                |
                       不合格且整改次数=2 -----> quality_failed -> END
                                                |
                                              达标
                                                |
                                 [interruptBefore review]
                                                |
                                              review
                                                |
                  +-- approved=false ----------+----> reject --> END
                  |
                  +-- approved=true ----------------> publish --> END
```
plan 只运行一次，初始化计数器与拆解任务。
revise 回到 research，而不是回到 plan，所以计数器不会被错误清零。
每次整改都重新运行两路分析并覆盖最终汇总。
两次整改意味着最多三次 quality 检查，不是只允许两次检查。

## 3. 可控输入契约
```json
{"topic":"企业知识助手的落地机会与风险","requiredRevisions":1}
```
- topic 为 1—500 字非空字符串。
- requiredRevisions 为非负 int 范围整数，不能用字符串 "2" 或小数 1.5。
- 0 表示初次分析即可达到本例的演示标准。
- 1 表示第一次 quality 不通过，整改一次后通过。
- 2 表示完整展示两次整改，然后进入人工审核。
- 3 或更大值表示两次整改后仍不达标，最终 quality_failed。
- sampleInput 使用 requiredRevisions=1，输入值均为 String 或 Number 标量。
- 这个字段控制教学门槛，不代表真实论文的客观质量分数。
- 不把模型自我评分当作控制循环次数或授权发布的依据。

## 4. 状态表
| 字段 | 类型 | 写入者 | 说明 |
| --- | --- | --- | --- |
| topic / requiredRevisions | String / Number | 输入、plan | 主题与可控演示门槛 |
| plan | List of String | plan | 机会分析、风险分析、交叉汇总任务 |
| revisionCount | Number | plan / revise | 已执行整改次数，范围 0—2 |
| marketAnalysis | String | analyze_market | 带 LOCAL-MARKET-01 来源的机会分析 |
| riskAnalysis | String | analyze_risk | 带 LOCAL-RISK-01 来源的风险分析 |
| analyses | List of Map | summarize | 最新两路结果，仅保留两个条目 |
| summary | String | summarize | 合并后的主题、机会、风险 |
| qualityPassed | Boolean | quality | 来源齐全且达到演示整改门槛 |
| qualityFeedback | String | quality | 门禁通过或未达标的原因 |
| sourceMode | String | plan | fixed-local-fixtures |
| generationMode | String | plan / publish | offline 或 dashscope |
| approved | Boolean | plan / resume | 初始 false，人工恢复时更新 |
| published | Boolean | plan / publish | 是否生成进程内模拟发布产物 |
| publicationId | String | publish | SIM-REPORT- 开头的模拟编号 |
| report | String | publish | 来源、模式与正文；offline 为完整汇总的固定模板，dashscope 为模型正文 |
| researchStatus | String | 业务节点 | researching / revising / awaiting_review / quality_failed / rejected / published 等 |
| trace | List of String | 每个节点 | 保留各轮的增量节点历史 |
初次 run 不接受 report、approved、revisionCount 等内部字段。
即使绕过 HTTP 直接给图传 approved=true，plan 也会重置审批状态。

## 5. 核心源码与本地证据
源码：[ResearchGraphDemo.java](https://github.com/ibqy/spring-ai-alibaba-graph-demo/blob/main/src/main/java/com/xb/graph/demo18/ResearchGraphDemo.java)。
类实现 GraphDemo，组件编号 demo18，级别 practice。
TextGenerator 通过构造器注入，bean 由第 12 课的公共配置提供。
本课没有注册重复 bean，也不要求额外模型 starter。
LOCAL-MARKET-01 是固定演示资料：从小范围知识问答试点验证价值。
LOCAL-RISK-01 是固定演示资料：关注过期知识、访问越权、幻觉与人工确认。
这些内容直接存在源码中，不是实时搜索、数据库查询或向量 RAG。
第一次整改在机会分析补充基线与评估，在风险分析补充资料有效期与指令边界。
第二次整改补充交叉验收、退出条件与负责人要求。
因此回边不只增加数字，还会改变被审核的本地分析正文。

## 6. 真并行、合并策略与有界回边
```java
AsyncNodeAction market = s -> CompletableFuture.supplyAsync(() -> analyzeMarket(s), executor);
AsyncNodeAction risk = s -> CompletableFuture.supplyAsync(() -> analyzeRisk(s), executor);
```
两个 future 提交到真实 Executor，图通过 research 扇出，通过 summarize 汇合。
只用 node_async 包装同步代码不能证明工作在线程间重叠。
marketAnalysis 与 riskAnalysis 分别写入不同状态键，不互相覆盖。
summary 和 analyses 使用 ReplaceStrategy，只保留最新一轮结果。
trace 使用 AppendStrategy，保留每一轮执行证据，但节点只返回本次增量。
质量条件边由 Java 明确控制：
```java
.addConditionalEdges("quality", edge_async(s -> Boolean.TRUE.equals(s.value("qualityPassed", false))
        ? "ready" : number(s, "revisionCount") < MAX_REVISIONS ? "retry" : "exhausted"),
        Map.of("ready", "review", "retry", "revise", "exhausted", "quality_failed"))
```
MAX_REVISIONS 为 2；达到上限且仍失败就终止，不继续调用生成器。
统一运行器的递归限制是额外保险，不能替代图中的业务返工上限。
测试使用 recursionLimit(50)，足以覆盖三轮分析和最后的审核分支。

## 7. 审核与生成边界
`reviewNodes()` 返回 `Set.of("review")`，运行器据此安装中断位置。
待审 state.summary 已具备两份来源与完整本地分析。
此时 report=""、publicationId=""、published=false。
拒绝只经过 reject，不调用 TextGenerator.generate。
批准后 publish 再检查 approved 与 qualityPassed，然后生成正文。
本课审批的是资料、汇总与生成许可，不代表人工逐字审过之后生成的正文。
若生产要求逐字审核，应该在生成草稿之后增加新的人工门禁。
report 包装明确写明生成模式、两个来源编号及“进程内模拟研报”。
离线正文将实际 summary 作为数据传给摘要替身，并另外完整保留 summary，不会只剩研报任务指令或两个来源编号。
真实模式将研报任务与 `<资料>` 内的汇总直接传给 DashScope，要求区分机会、风险与局限、引用来源、只输出纯文本；主题和资料不能当执行指令。
LLM 返回文本只进入 report，不解析成工具参数、脚本或执行命令。
即使正文声称“立即外部发布”，Java 也没有对应的外部发布动作。

## 8. 启动服务与模型模式
在项目根目录的 Git Bash 中启动：
```bash
export JAVA_HOME='C:/path/to/jdk-21'
export PATH="$JAVA_HOME/bin:$PATH"
export GRAPH_MODEL_MODE=offline
mvn -B -ntp spring-boot:run
```
offline 无须密钥；公共生成器只对实际 summary 做确定性摘要预览，不截取研报指令。
最终 report 使用明确标注“不进行语义推理”的固定模板，完整保留主题、两路本地分析及所有整改补充，另列资料局限。
同一主题与整改次数产生相同正文；换主题或整改内容会改变正文，不只是来源编号不同。这不是语义研究或事实核验。
本地资料、图分支、并行与审批恢复都真实执行。
使用 dashscope 时，在本地安全设置 DASHSCOPE_API_KEY 并改 GRAPH_MODEL_MODE=dashscope。
公开示例不要写入真实密钥，也不要把机密主题和内部资料发送给外部模型。
切换模式不改变固定资料来源，更不意味着联网搜索了事实。
另开终端，以下命令需要 curl 和 jq。

## 9. run → executionId → 批准报告
```bash
BASE=http://127.0.0.1:8098
curl -sS "$BASE/api/demos/demo18/graph"
pending=$(curl -sS -X POST "$BASE/api/demos/demo18/run" \
  -H 'Content-Type: application/json' \
  -d '{"input":{"topic":"企业知识助手的落地机会与风险","requiredRevisions":2}}')
executionId=$(jq -r '.executionId' <<< "$pending")
jq '{executionId,status,state,visitedNodes}' <<< "$pending"
```
待审预期：status=WAITING_REVIEW、revisionCount=2、qualityPassed=true。
state.analyses 长度为 2，不会因为返工变成 6。
state.summary 同时包含 LOCAL-MARKET-01 与 LOCAL-RISK-01。
```bash
curl -sS -X POST "$BASE/api/executions/$executionId/resume" \
  -H 'Content-Type: application/json' -d '{"approved":true}' \
  | jq '{status,researchStatus:.state.researchStatus,published:.state.published,publicationId:.state.publicationId,report:.state.report}'
```
预期 status=COMPLETED、researchStatus=published、published=true。
publicationId 为 SIM-REPORT- 编号，report 含来源与 offline 或 dashscope 标记。
不同模式正文可能不同；固定来源、审批条件和最大整改次数不会随模型变化。

## 10. 另建执行演示拒绝
```bash
pending=$(curl -sS -X POST "$BASE/api/demos/demo18/run" \
  -H 'Content-Type: application/json' \
  -d '{"input":{"topic":"尚不适合发布的研究","requiredRevisions":0}}')
executionId=$(jq -r '.executionId' <<< "$pending")
curl -sS -X POST "$BASE/api/executions/$executionId/resume" \
  -H 'Content-Type: application/json' -d '{"approved":false}' \
  | jq '{status,researchStatus:.state.researchStatus,published:.state.published,report:.state.report,publicationId:.state.publicationId}'
```
拒绝后 researchStatus=rejected、published=false、report=""、publicationId=""。
summary 仍保留供学习查看，不等于产生已批准报告。
不要对已结束的执行再次 resume；需要另一结果时新建 run。

## 11. 演示返工上限
```bash
curl -sS -X POST "$BASE/api/demos/demo18/run" \
  -H 'Content-Type: application/json' \
  -d '{"input":{"topic":"两次整改后仍不达标","requiredRevisions":99}}' \
  | jq '{status,researchStatus:.state.researchStatus,revisionCount:.state.revisionCount,qualityPassed:.state.qualityPassed,trace:.state.trace}'
```
预期直接 COMPLETED，researchStatus=quality_failed、revisionCount=2。
qualityPassed=false；trace 中 revise 恰好两次，quality 恰好三次。
不会暂停等待审核，也没有 review 或 publish 的执行轨迹。
因此这个 executionId 不应该再用于人工 resume。

## 12. 检查点、输出与测试证据
统一响应包含 executionId、demoId、status、state、visitedNodes。
MemorySaver 按 threadId 保存检查点，executionId 关联这次执行。
恢复只更新 approved，使用 updateState 返回的新配置调用 `stream(null, updated.withResume())`。
恢复时不允许改 topic、requiredRevisions 或已汇总来源。
并行分支完成顺序不是稳定契约，不能依靠谁先完成决定质量结果。
state.trace 记录业务节点，visitedNodes 可能包含框架并行聚合输出。
测试入口：[PracticeGraphTest.java](https://github.com/ibqy/spring-ai-alibaba-graph-demo/blob/main/src/test/java/com/xb/graph/PracticeGraphTest.java)。
测试用双工作线程屏障验证两路分析实际并行，不使用 sleep。
测试断言初次汇总同时包含两路来源、审前没有报告、通过后存在带来源正文。
requiredRevisions=2 验证恰好两次整改后待审；99 验证达到上限直接终止。
拒绝测试的生成器一旦被调用便报错，真实拒绝路径必须完全不调用它。
离线正文回归测试使用 TextGeneratorConfiguration 配置工厂与 MockEnvironment 创建真实 offline Bean，不只用手写 fake。
两个不同主题都验证发布正文包含实际主题、完整两路证据和两次整改内容，正文彼此不同而同输入重复生成一致；只保留来源编号无法通过。
其他隔离测试仍可使用 fake；图、保存器、条件边、检查点恢复都不是 mock，全程不调用网络。
```bash
mvn -B -ntp -Dtest=PracticeGraphTest test
```

## 13. 失败与生产边界
- topic 为空、超长或 requiredRevisions 非法会失败，不产生发布产物。
- 模型请求失败时不会设置 published=true，应由应用处理失败执行与重试策略。
- 来源标记不是事实核验结果，模型可能曲解资料，人工仍需审阅。
- 固定演示素材不能用于投资、法律或真实商业决策。
- MemorySaver 与模拟 publicationId 没有跨重启持久性，也不是外部发布幂等协议。
- 生产并行任务需要受管理执行器、超时、取消、限流与资料版本一致性。
- 真实质量门禁应验证引用可追溯性、事实一致性与任务完成度，而不是人工输入次数。
- 本例没有外发接口；增加外部发布前需独立授权、审批版本、审计与幂等适配器。
- 渲染报告时必须转义输出，不能把模型正文解释成可执行内容。

## 14. 练习
1. 依次输入 0、1、2、99，对比 revise 与 quality 的出现次数。
2. 对比每轮 summary，指出第一次与第二次整改实际补充了哪些内容。
3. 解释为什么 analyses 要覆盖更新，而 trace 应该追加更新。
4. 设计“先生成、再逐字审核、再发布”的新图，并标出所有副作用边界。
5. 思考需要跨进程恢复时，检查点、原始资料版本、审批人身份应如何持久化。
