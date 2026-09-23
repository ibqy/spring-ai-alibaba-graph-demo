# 01 · Hello Graph：第一个节点

## 目标与前置

- 用一个真实 `StateGraph` 把输入 `name` 转成 `greeting`。
- 分清图定义、编译后的图、一次执行的状态三种对象。
- 前置：Java 21、Spring Boot 3.5.8、Graph 1.1.2.2，项目依赖已解析。
- 本课没有模型调用，不需要 API Key，也不会访问外部服务。
- 实现类是 `com.xb.graph.demo01.HelloGraphDemo`，目录名与课程编号一一对应。

## 流程图

```text
START -- {name} --> greet -- {greeting} --> END
```

`START` 和 `END` 是框架提供的控制节点，不是我们编写的问候函数。
`greet` 是唯一业务节点，它只返回新增字段，不返回整个状态。
边决定执行顺序；Java 文件中方法的排列并不决定节点顺序。

## 输入与逐节点状态

| 时刻 | 可观察的状态 |
| --- | --- |
| 输入 | `{"name":"小北"}` |
| greet 返回的更新 | `{"greeting":"你好，小北！欢迎学习 Graph。"}` |
| 合并后 / END | 同时具有原始 `name` 与新的 `greeting` |

节点不删除 `name`，所以最终状态不是只有一句问候。
输入 `" 小南 "` 会生成 `你好，小南！欢迎学习 Graph。`。
问候中的名字会去除首尾空白，但原始 `name` 字段仍然保留原值。
这是本课的业务选择，不是 Graph 自动清洗字符串。

## 核心 API 与设计原因

- `new StateGraph(id(), keyStrategyFactory)` 声明拓扑与字段合并策略。
- `ReplaceStrategy` 使同一个字段的新值覆盖旧值。
- `node_async` 把同步节点函数适配为框架接受的异步接口。
- 这个适配器本身不表示节点已被安排到另一个线程。
- `state.value("name")` 读取当前状态，节点返回 `Map` 表达局部更新。
- `addEdge(START, "greet")` 与 `addEdge("greet", END)` 让路径完整闭合。
- `compile()` 校验图定义并产生可执行图；`invoke(input)` 返回最终状态。
- `@Component` 和 `GraphDemo` 是本项目的发现约定，不是 StateGraph 的必需条件。

## 真实源码

下方直接包含 Java 文件，避免教程里的代码与实现分叉。

<<< ../../src/main/java/com/xb/graph/demo01/HelloGraphDemo.java

已有 `HelloGraphTest.greetsTheProvidedLearnerThroughTheGraph` 直接编译并执行这张图。
它精确断言 `你好，小北！欢迎学习 Graph。`，不是用 Mock 代替节点。

## 运行请求

启动本地 Java 服务后执行；文档站本身不承载 Java API。

```bash
curl http://127.0.0.1:8098/api/demos
curl http://127.0.0.1:8098/api/demos/demo01/graph
curl -X POST http://127.0.0.1:8098/api/demos/demo01/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"name":"小北"}}'
```

统一返回形状为 `{executionId,demoId,status,state,visitedNodes}`。
`executionId` 由运行层生成，`demoId` 为 `demo01`，正常结束为 `COMPLETED`。
检查 `state.greeting`，业务访问节点为 `greet`；不要把执行 ID 当作固定示例值。
`GET /graph` 展示由真实图生成的 Mermaid，不是另一份手写流程图。

## 手工推导与验证

1. 输入 Map 里只有 `name=小北`。
2. greet 验证名字非空，去掉首尾空白，拼接固定前后缀。
3. 合并策略把 greeting 写入状态，name 不受影响。
4. 到达 END，得到预期问候；没有循环或第二次问候。

```bash
mvn -B -ntp -Dtest=HelloGraphTest,BasicGraphTest test
```

## 异常与边界

- 直接 Graph 调用缺少 name、传入非字符串或空白字符串，节点拒绝执行。
- 统一 HTTP 运行层会先以 sampleInput 补缺省字段；直接 Graph API 不会这样做。
- HTTP 的输入字段白名单与长度校验属于本项目运行层，不是图引擎的字符串规则。
- 本课没有重试、副作用或持久化承诺；异常不等同于自动补偿。

## 练习

1. 把 name 换成自己的名字，先写出 greeting 再运行核对。
2. 用 BasicGraphTest 的写法增加另一种合法名字测试。
3. 思考：若 greet 返回完整旧状态，未来增加 Append 字段时会发生什么？
4. 下一课保留原始输入，增加独立的清洗结果，观察两个节点如何交接。
