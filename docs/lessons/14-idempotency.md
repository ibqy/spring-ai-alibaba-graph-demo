# 第 14 课：并发安全的模拟副作用幂等

## 学习目标
- 把重复执行图与重复执行副作用区分开：节点可以再次运行，副作用不能随意重复。
- 使用 ConcurrentHashMap.computeIfAbsent 在同一进程内原子地创建模拟回执。
- 同时识别相同请求重放与同一 key 对应不同 payload 的冲突。
- 明确这是内存教学模型，不是生产支付实现或分布式 exactly-once 保证。

## 图结构
```text
START -> validate -> apply -> report -> END
                       |
            ConcurrentHashMap<key, Receipt>
              /            |            \
           key不存在    key存在同payload   key存在异payload
            APPLIED       REPLAYED        CONFLICT
```
三个 outcome 是 apply 写入的业务状态，本课不为每种结果再建一个独立图节点。
report 根据 outcome 返回明确的结果说明，所有情况都可在统一响应中观察。
冲突不是重放成功，调用方必须检查 state.outcome。

## 输入与逐节点状态
输入 idempotencyKey 和 payload 都必须是非空字符串。
key 表示调用方为一次业务意图分配的稳定编号，不是每次 HTTP 请求的新 UUID。
payload 在本课按字符串精确比较，空格变化也可能视为不同材料。
| 节点 | 读取 | 写入 |
| --- | --- | --- |
| validate | idempotencyKey、payload | simulation=true |
| apply | 两个经过校验的字符串 | outcome、effectId、replayed、totalEffects |
| report | outcome | message |
每个节点向 trace 追加实际完成的节点名。
effectId 是已有或新建的模拟回执编号，例如 sim-effect-1。
CONFLICT 中 effectId 指向占用该 key 的已有回执，不表示新 payload 已执行成功。
replayed=true 仅表示同 key 同 payload 的复用，不包含冲突。
totalEffects 是此单例从创建以来的模拟副作用总数，不是当前执行的节点数量。

## API 与并发设计原因
`IdempotencyDemo` 是 Spring 单例组件，receipts 与 effects 是实例字段。
每次 graph() 创建新图，但新图访问同一个实例上的幂等表。
把表建在 graph() 内部会导致每次执行都忘记以前的 key，不能实现跨请求幂等。
`computeIfAbsent` 将同 key 的首次创建合并成一个原子操作。
只有回调真正执行时，AtomicInteger 才加一并分配模拟回执。
同 key 的其他调用取得同一个不可变 Receipt，再精确比较 payload。
AtomicBoolean 只记录本次调用是否创建回执，是局部变量，不在线程间共享结果状态。
不能改成 containsKey 后再 put；检查与写入之间的竞争会重复执行副作用。
真正的“副作用”只有本地计数和回执创建，没有扣款、银行请求或外部资源修改。
computeIfAbsent 的回调不能被当成外部数据库和支付系统的原子事务。

## 实际请求：首次与重放
请在刚启动的教学服务中连续执行下面两条完全相同的请求。
```bash
curl -sS -X POST http://127.0.0.1:8098/api/demos/demo14/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"idempotencyKey":"order-1","payload":"教学积分+10"}}'
curl -sS -X POST http://127.0.0.1:8098/api/demos/demo14/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"idempotencyKey":"order-1","payload":"教学积分+10"}}'
```
首次 state.outcome=APPLIED、state.replayed=false、state.totalEffects=1。
第二次 state.outcome=REPLAYED、state.replayed=true，effectId 与第一次相同。
第二次 totalEffects 仍为 1，不随重复 HTTP 请求增加。
两次 executionId 不同，说明“执行会话编号”与“业务幂等键”不是同一概念。
两次 status 都为 COMPLETED，visitedNodes 都是 `["validate","apply","report"]`。
统一响应还包含 demoId=demo14 与完整 state。

## 实际请求：冲突与图查看
```bash
curl -sS -X POST http://127.0.0.1:8098/api/demos/demo14/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"idempotencyKey":"order-1","payload":"教学积分+20"}}'
curl -sS http://127.0.0.1:8098/api/demos/demo14/graph
curl -N http://127.0.0.1:8098/api/demos/demo14/stream
```
冲突预期 outcome=CONFLICT、replayed=false，不增加副作用计数。
message 明确包含“同一 key 对应不同 payload”，不能把旧回执当新请求成功。
GET graph 只生成 Mermaid，不运行副作用。
GET stream 会真实运行样例，样例 key=lesson-order-001，payload=教学积分+10。
第一次样例流可能新增一份模拟回执，第二次流则复用它；流接口并非纯查询历史。
因此已经运行过其他样例时，不要再把 totalEffects 固定期待为 1。

## 并发观察
自动测试创建 12 个虚拟线程，用起跑闩锁让重复请求并发进入。
所有请求共享一个 IdempotencyDemo，但使用各自的图、MemorySaver 和 threadId。
预期恰有一个 APPLIED，另外 11 个 REPLAYED，所有 effectId 相同。
测试同时断言 totalEffects=1，避免“回执相同但副作用已重复”的假实现。
不同 key 可以各自创建回执，totalEffects 会随不同业务意图增加。
并发存在其他 key 时，totalEffects 是当时观测到的全局数，不是隔离事务快照。

## 内存方案的限制与异常
- 空 key 或空 payload 会在 validate 阶段失败，不创建回执。
- 服务重启后 Map 和计数器都丢失，同样的 key 会重新被视为首次请求。
- 多实例部署时各实例有自己的 Map，不能阻止跨实例重复。
- 此表没有过期淘汰和大小限制，仅适合有限教学请求。
- 永久保存完整 payload 可能涉及隐私；生产需要数据保留与脱敏策略。
- 外部副作用与幂等记录必须考虑事务、一致性和崩溃窗口，不能照搬本课计数器。
- 回调抛出异常时没有成功回执，后续调用可能再次尝试，这不等于自动回滚外部副作用。

## 源码与测试入口
- `src/main/java/com/xb/graph/demo14/IdempotencyDemo.java`：实例表、computeIfAbsent 与冲突比较。
- `src/test/java/com/xb/graph/AdvancedGraphTest.java`：重复、不同 key、并发重复与异 payload 冲突。
测试无需网络、外部数据库或真实支付凭证。

## 练习
1. 重启服务后重新发送 order-1，解释为何会再次 APPLIED。
2. 让两个并发请求使用同 key 不同 payload，推演谁首次成功、谁冲突。
3. 讨论真实业务应对 payload 先做何种规范化，避免字段顺序造成假冲突。
4. 设计数据库唯一约束、请求摘要和事务发件箱如何协同解决跨实例问题。
