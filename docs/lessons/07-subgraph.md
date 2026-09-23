# 07 · 子图组合：把流程作为节点复用

## 目标与前置

- 在父 StateGraph 中嵌入另一张真实 StateGraph，而非调用一个普通辅助函数。
- 观察父图输入如何进入子图，以及子图结果怎样供父图后续节点读取。
- 前置：第 01–03 课的节点、边、状态合并概念。
- 实现类：`com.xb.graph.demo07.SubgraphDemo`，级别 advanced。
- 子图是 greeting-child，父图中承载它的节点名是 greetingChild。

## 流程图

```text
父图 START --> prepare --> greetingChild --> finish --> END
                              |
          子图 START --> normalize --> greet --> END
```

prepare 写入来源标记，子图负责名字规范化与问候，finish 负责包装输出。
这三个职责刻意分开，便于观察组合边界。
子图并不表示另一个服务，也不自动获得独立事务或安全边界。

## 输入与逐节点状态

默认输入：`{"name":" 小北 "}`。

| 节点 | 可观察的新字段 |
| --- | --- |
| 父 prepare | `source="parent"` |
| 子 normalize | `cleanedName="小北"` |
| 子 greet | `greeting="子图你好，小北！"` |
| 父 finish | `result="[子图你好，小北！]"` |

name 原值保留，不会因为子图清洗而被覆盖。
父 finish 读取的是子图产出的 greeting，不是自行重新计算问候。
最终 state 同时包含 name、source、cleanedName、greeting、result。
如果子图没返回 greeting，finish 的 orElseThrow 会暴露缺失输出。

## 核心 API 与设计原因

- 子图与父图都使用 StateGraph 构造，而不是把子流程硬编码到运行框架。
- `addNode("greetingChild", child)` 使用接收 StateGraph 的真实重载。
- 父图编译时会处理子图结构，调用方仍通过父图入口执行。
- 本课用相同的字段策略表描述父子共享数据，避免字段合并含义不一致。
- 字段均用 ReplaceStrategy，结果代表当前值，不代表跨边界追加日志。
- 如果父子都回传同一份 Append 列表，必须仔细验证是否重复合并。
- 子图输入输出不是 Java 方法签名里的强类型 DTO，字段约定仍需测试保护。
- 子图的 normalize 与 greet 是真实节点，可被图引擎观察和调度。
- `@Component` 只注册本课 GraphDemo；不必把每个子图都做成 Spring Bean。
- 本课每次 graph() 创建图定义，不把会话数据存进单例对象成员。

## 真实源码

<<< ../../src/main/java/com/xb/graph/demo07/SubgraphDemo.java

`BasicGraphTest.parentReceivesActualChildGraphUpdates` 直接调用父图。
测试断言 cleanedName 与 greeting 均存在，同时验证父 finish 使用了子图结果。
因此把子图删除或断开父子连边会使测试失败。

## 运行请求

```bash
curl http://127.0.0.1:8098/api/demos
curl http://127.0.0.1:8098/api/demos/demo07/graph
curl -X POST http://127.0.0.1:8098/api/demos/demo07/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"name":" 小北 "}}'
```

响应形状为 `{executionId,demoId,status,state,visitedNodes}`。
正常结束 status 为 COMPLETED，重点检查 `state.result` 的方括号包装。
visitedNodes 取自框架节点输出；子图标识与命名可能包含框架上下文。
不要把所有子图流事件机械地解释为父图节点，更不要把子图 END 当作整个运行结束。
图接口返回实际图的 Mermaid 表示，可用于核对嵌套结构。

## 手工推导

1. prepare 添加 source=parent，不修改 name。
2. 子图 normalize 去掉 name 两端空白，保存 cleanedName=小北。
3. 子图 greet 拼接得到 `子图你好，小北！`。
4. 子图结束后父 finish 为 greeting 加上方括号。
5. 父图 END 的 result 为 `[子图你好，小北！]`，不是 Hello 课的问候模板。

## 异常与边界

- 子图 normalize 拒绝空白或非字符串名字，异常会影响父图执行。
- 子图不是错误隔离容器；本课没有失败降级或自动重试策略。
- 共享字段名是明确约定，复杂父子状态应考虑显式字段映射与边界测试。
- 本课不演示子图专用 Saver 或跨图恢复，不能据此假设任意恢复行为。
- HTTP 缺省输入由统一运行层补齐，直接 Graph API 不补 sampleInput。
- 图定义复用与运行状态复用是两件事，下一课进一步拆开编译配置与执行配置。

## 练习

1. 将子图问候结果改成另一个字段前，先列出父图读取它的位置。
2. 新增子图内部节点，确认无需修改统一运行层也能执行。
3. 若多个子图都写 result，应怎样命名以避免父图合并冲突？
4. 编写一个子图非法输入测试，验证父图不会误报完整结果。
