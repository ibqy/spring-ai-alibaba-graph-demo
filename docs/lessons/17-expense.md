# 第 17 课：报销并行校验、审批与原子幂等入账

## 1. 业务目标
- 从规范化 receipt 文本中用正则规则抽取金额、类别、发票号。
- 真正并行执行单据校验与类别限额校验，再进行 fan-in 聚合。
- 任意校验失败直接拒绝，避免把明显不合格的申请送给审核人。
- 只有校验通过才在 review 前暂停，批准之后才允许模拟入账。
- 同一 requestId 与同一金额返回同一凭证，不同金额返回冲突。
- 本课不做 OCR、不调用 LLM，也不连接银行或财务系统。
- “入账”只是 ConcurrentHashMap 中的一条进程内记录，没有真实转账。

## 2. ASCII 节点图
```text
START -> extract
           |
           +----> validate_receipt ----+
           |                          |
           +----> validate_limit ------+--> aggregate
                                               |
                            校验失败 -----------+----> reject --> END
                                               |
                                            校验通过
                                               |
                                [interruptBefore review]
                                               |
                                             review
                                               |
                        +-- approved=false ----+----> reject --> END
                        |
                        +-- approved=true ----------> book --> END
                                                       |
                                       同键同金额：复用凭证
                                       同键异金额：conflict
```
validate_receipt 与 validate_limit 都完成后才执行 aggregate。
并行分支不会修改台账，也不会提前占用 requestId。
book 是唯一能访问并写入模拟台账的业务节点。

## 3. 输入格式与规则
首个输入示例：
```json
{"receipt":"金额=128.50;类别=交通;发票号=INV-001","requestId":"EXP-001"}
```
receipt 是文本，而不是图片地址或二进制文件。
字段间允许英文分号、中文分号或换行，字段名和值之间允许 `=`、`:`、`：`。
金额允许正数以及最多两位小数，可带“元”；零、负数、科学计数法不合格。
金额转换为整数分，使用 BigDecimal 和 longValueExact，不用 double 做金额比较。
`128.5` 与 `128.50` 都转为 12850 分，所以幂等比较结果一致。
支持类别：交通限额 500 元，餐饮限额 200 元，住宿限额 1000 元。
等于限额可以通过，超过一分钱也会拒绝。
发票号只接受 1—40 位英文字母、数字与连字符。
requestId 去掉首尾空白后，必须为 1—64 位英文字母、数字、下划线或连字符。
每个必需字段只能声明一次；即使重复字段中有一个值非法，也整体拒绝。
例如 `金额=abc;金额=20` 不会被当作合法 20 元。
这些只是教学语法规则，不代表验证过税务发票真实性。

## 4. 状态表
| 字段 | 类型 | 生产节点 | 解释 |
| --- | --- | --- | --- |
| receipt / requestId | String | 输入 | 票据文本 / 业务幂等键 |
| amountCents | Number | extract | 精确整数分；金额必须结合 amountValid 判断 |
| amountValid | Boolean | extract | 正金额且格式、精度、范围合法 |
| category / invoiceId | String | extract | 规则抽取的类别与编号 |
| extractionMode | String | extract | regex-not-ocr-not-llm |
| receiptValid / receiptReason | Boolean / String | validate_receipt | 单据校验结果及原因 |
| limitValid / limitReason | Boolean / String | validate_limit | 限额校验结果及原因 |
| limitCents | Number | validate_limit | 当前类别限额，单位为分 |
| validationPassed | Boolean | aggregate | 两个校验都通过 |
| approved | Boolean | 初始化 / resume | 人工决定，初始 false |
| expenseStatus | String | 各节点 | validating / awaiting_review / rejected / posted / conflict |
| rejectionReason | String | aggregate / reject / book | 自动拒绝、人工拒绝或冲突说明 |
| posted / voucherId | Boolean / String | book | 本次是否成功取得模拟凭证 |
| trace | List of String | 每个节点 | 增量业务轨迹 |
样例输入只包含 receipt 与 requestId，不开放 amountCents、approved 等内部字段。
初始 posted=false、voucherId=""，审核前没有台账预占或凭证生成。

## 5. 核心源码：真正异步并行
源码：[ExpenseGraphDemo.java](https://github.com/ibqy/spring-ai-alibaba-graph-demo/blob/main/src/main/java/com/xb/graph/demo17/ExpenseGraphDemo.java)。
`@Component` 注册 demo17，级别为 practice，`reviewNodes()` 返回 `Set.of("review")`。
`graph()` 用 StateGraph 建立 fan-out、fan-in 和两组条件边。
```java
AsyncNodeAction receiptCheck = s -> CompletableFuture.supplyAsync(() -> validateReceipt(s), executor);
AsyncNodeAction limitCheck = s -> CompletableFuture.supplyAsync(() -> validateLimit(s), executor);
```
这里显式提交 future；仅把函数包装成 node_async 不会自动得到线程并行。
默认执行器为公共线程池；构造器也允许传入应用管理的 Executor。
本组件不在每次请求中创建线程池，也不擅自关闭调用方的执行器。
```java
.addEdge("extract", "validate_receipt")
.addEdge("extract", "validate_limit")
.addEdge("validate_receipt", "aggregate")
.addEdge("validate_limit", "aggregate")
```
两个分支各写自己的结果字段，不共享可变列表。
trace 使用 AppendStrategy，返回的是本节点新增元素，不是完整历史副本。
aggregate 用两个布尔校验结果相与；失败直达 reject，没有 review。
真实测试的双线程屏障要求两路任务同时到达，串行伪装会超时失败。
测试没有用 sleep 制造“看起来并行”的耗时效果。

## 6. 核心源码：原子幂等模拟台账
```java
Booking booking = ledger.compute(requestId, (key, existing) -> existing != null ? existing
        : new Booking(cents, "SIM-VOUCHER-" + UUID.randomUUID()));
```
检查旧记录与创建新记录在同一个原子 compute 内完成。
不能改成先 `containsKey` 再 `put`，否则并发请求会产生不同凭证。
返回记录的金额与当前金额相同，就返回相同 voucherId。
不同金额返回 expenseStatus=conflict，posted=false，不覆盖原始台账。
同金额但不同类别仍按本课的金额契约复用；生产应比较完整业务指纹。
book 内还检查 validationPassed 与 approved，不能只依赖边的命名。
台账属于组件实例，同一服务进程中新建图仍共享该台账。
requestId 是跨执行幂等键，executionId 是本次图检查点标识，两者不能混淆。

## 7. 启动与查看图
在项目根目录执行，使用 Java 21 与项目现有依赖。
```bash
export JAVA_HOME='C:/path/to/jdk-21'
export PATH="$JAVA_HOME/bin:$PATH"
export GRAPH_MODEL_MODE=offline
mvn -B -ntp spring-boot:run
```
本课不使用生成器，但完整应用在 offline 模式下启动无需云端 Key。
在另一个安装了 curl、jq 的 Git Bash 中执行：
```bash
BASE=http://127.0.0.1:8098
curl -sS "$BASE/api/demos/demo17/graph"
```

## 8. 合格单据：run → executionId → 批准
```bash
pending=$(curl -sS -X POST "$BASE/api/demos/demo17/run" \
  -H 'Content-Type: application/json' \
  -d '{"input":{"receipt":"金额=128.50;类别=交通;发票号=INV-001","requestId":"EXP-001"}}')
executionId=$(jq -r '.executionId' <<< "$pending")
jq '{executionId,status,state,visitedNodes}' <<< "$pending"
```
先检查 status=WAITING_REVIEW、receiptValid=true、limitValid=true。
此时 expenseStatus=awaiting_review，posted=false，voucherId=""。
```bash
approved=$(curl -sS -X POST "$BASE/api/executions/$executionId/resume" \
  -H 'Content-Type: application/json' -d '{"approved":true}')
jq '{status,expenseStatus:.state.expenseStatus,posted:.state.posted,voucherId:.state.voucherId}' <<< "$approved"
```
预期 status=COMPLETED、expenseStatus=posted、posted=true。
记住返回的 SIM-VOUCHER- 编号，后面用于验证幂等。

## 9. 人工拒绝：另一条执行不能入账
```bash
pending=$(curl -sS -X POST "$BASE/api/demos/demo17/run" \
  -H 'Content-Type: application/json' \
  -d '{"input":{"receipt":"金额=80;类别=餐饮;发票号=INV-002","requestId":"EXP-DENY-001"}}')
executionId=$(jq -r '.executionId' <<< "$pending")
curl -sS -X POST "$BASE/api/executions/$executionId/resume" \
  -H 'Content-Type: application/json' -d '{"approved":false}' \
  | jq '{status,expenseStatus:.state.expenseStatus,posted:.state.posted,voucherId:.state.voucherId}'
```
预期 expenseStatus=rejected、posted=false、voucherId=""。
status 仍为 COMPLETED，因为“拒绝”也是正常业务终点。
拒绝的 requestId 未被写入台账，以后可以发起新的申请。

## 10. 自动拒绝、幂等重试与金额冲突
```bash
curl -sS -X POST "$BASE/api/demos/demo17/run" \
  -H 'Content-Type: application/json' \
  -d '{"input":{"receipt":"金额=500.01;类别=交通;发票号=INV-003","requestId":"EXP-OVER-001"}}' \
  | jq '{status,state,visitedNodes}'
```
超限申请直接 COMPLETED，expenseStatus=rejected，不需要也不能 resume。
把金额改成 0、-1、abc、12.345 或 1e3，观察金额校验失败。
```bash
pending=$(curl -sS -X POST "$BASE/api/demos/demo17/run" \
  -H 'Content-Type: application/json' \
  -d '{"input":{"receipt":"金额=128.5;类别=交通;发票号=INV-001","requestId":"EXP-001"}}')
executionId=$(jq -r '.executionId' <<< "$pending")
curl -sS -X POST "$BASE/api/executions/$executionId/resume" \
  -H 'Content-Type: application/json' -d '{"approved":true}' | jq '.state'
```
第二次同键同金额也经过审核，通过后取得与第 8 节完全相同的 voucherId。
再新建 run，将金额改成 129.00、requestId 仍为 EXP-001，然后批准。
预期 expenseStatus=conflict、posted=false、voucherId=""，原凭证不被改写。
冲突是业务状态，不要把它误解为本教程承诺的 HTTP 409 响应。

## 11. 检查点与测试
恢复请求只允许 `{"approved":true}` 或 `{"approved":false}`。
review 前的检查点保存了抽取与校验结果，resume 不允许修改金额或幂等键。
运行器以 executionId 关联 MemorySaver 的 threadId，恢复时使用 updateState 返回的新配置。
state.trace 能显示两个校验节点；分支先后顺序不属于业务契约。
visitedNodes 来自运行器的图输出，在并行图中可能包含框架的并行聚合节点。
测试入口：[PracticeGraphTest.java](https://github.com/ibqy/spring-ai-alibaba-graph-demo/blob/main/src/test/java/com/xb/graph/PracticeGraphTest.java)。
测试覆盖审批前无入账、通过、拒绝、无效金额、重复声明、缺失发票和超限。
8 个并发执行使用相同 requestId，同时批准后必须返回唯一凭证。
测试还验证不同金额冲突后，原金额重试依然能取回原凭证。
所有测试执行真实 StateGraph，没有 mock 图或 mock 台账。
```bash
mvn -B -ntp -Dtest=PracticeGraphTest test
```

## 12. 生产边界与练习
- ConcurrentHashMap 不是数据库，重启会丢失台账，多实例之间也不共享。
- 没有真实转账、税务验真、会计分录、发票防重或退款补偿。
- 生产必须使用数据库唯一约束与事务，幂等键还应包含租户和业务主体。
- 生产需要明确币种、舍入规则、金额上限、会计期间与完整请求指纹。
- 审批权限、金额版本、审计身份不能由客户端自行声明。
- 远程验真还需要超时、失败闭合与重试策略，不能把异常默认当通过。
- 财务内容可能敏感，不应把 receipt 原文或凭证直接写入开放日志。
1. 比较 500.00 与 500.01 元的交通报销路径。
2. 构造一个“合法金额 + 非法重复金额”，说明为什么应整体拒绝。
3. 观察人工拒绝与自动拒绝各经过哪些节点。
4. 解释 requestId 与 executionId 的差异，并设计跨服务幂等方案。
5. 尝试证明把 compute 拆成查询再写入为什么会破坏并发安全，而不要接入真实付款。
