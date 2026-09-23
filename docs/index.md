---
layout: home
markdownStyles: false
title: 把流程，写成一张图
description: 18 个真实 StateGraph Demo，使用 Spring AI Alibaba 1.1.2.2、Java 21 与 Spring Boot 3.5.8，从基础编排到业务实战，默认无 Key 本地运行。
---

<div class="course-home">

<HomeHero />

<div class="version-strip" aria-label="课程技术版本">
  <div><span class="version-label">真实 Graph 引擎</span><strong>StateGraph <span>1.1.2.2</span></strong></div>
  <div><span class="version-label">语言环境</span><strong>Java <span>21</span></strong></div>
  <div><span class="version-label">应用框架</span><strong>Spring Boot <span>3.5.8</span></strong></div>
</div>

<section class="curriculum" aria-labelledby="curriculum">
<div class="section-heading">
<div>
<p class="eyebrow">THE LEARNING PATH / 18 LESSONS</p>

## 课程地图 {#curriculum}

<p class="section-intro">先学会编排，再理解执行，最后串起业务。每一课，只向前走一步。</p>
</div>
<nav class="course-jump" aria-label="跳转到课程阶段">

[基础](#basics) [高阶](#advanced) [实战](#practice)

</nav>
</div>

<section class="curriculum-group" aria-labelledby="basics">
<div class="group-heading">
<p class="group-index">01—06 <span>/ FOUNDATION</span></p>

### 基础 · 把图跑起来 {#basics}

<p>节点、状态与边。<br>建立对 Graph 的第一层直觉。</p>
</div>
<div class="lesson-grid">

[<span class="lesson-number">01</span><span class="lesson-copy"><strong>Hello Graph</strong><span>从 START 到 END，运行第一张图</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/01-hello)

[<span class="lesson-number">02</span><span class="lesson-copy"><strong>顺序编排</strong><span>连接多个节点，追踪执行顺序</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/02-sequence)

[<span class="lesson-number">03</span><span class="lesson-copy"><strong>状态管理</strong><span>理解状态的读取、更新与合并</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/03-state)

[<span class="lesson-number">04</span><span class="lesson-copy"><strong>条件路由</strong><span>让状态决定下一步走向</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/04-routing)

[<span class="lesson-number">05</span><span class="lesson-copy"><strong>循环控制</strong><span>建立回路，也明确退出条件</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/05-loop)

[<span class="lesson-number">06</span><span class="lesson-copy"><strong>并行分支</strong><span>拆分任务，再汇合处理结果</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/06-parallel)

</div>
</section>

<section class="curriculum-group" aria-labelledby="advanced">
<div class="group-heading">
<p class="group-index">07—15 <span>/ ENGINEERING</span></p>

### 高阶 · 让图可控 {#advanced}

<p>从能执行，到理解执行边界。<br>处理恢复、交互与工程问题。</p>
</div>
<div class="lesson-grid">

[<span class="lesson-number">07</span><span class="lesson-copy"><strong>子图组合</strong><span>将一段工作流封装为可组合的图</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/07-subgraph)

[<span class="lesson-number">08</span><span class="lesson-copy"><strong>检查点</strong><span>理解执行状态的保存与恢复</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/08-checkpoint)

[<span class="lesson-number">09</span><span class="lesson-copy"><strong>执行隔离</strong><span>区分不同执行上下文的状态</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/09-isolation)

[<span class="lesson-number">10</span><span class="lesson-copy"><strong>人工审核</strong><span>暂停流程，等待人的决定</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/10-human-review)

[<span class="lesson-number">11</span><span class="lesson-copy"><strong>流式输出</strong><span>观察执行过程中的增量事件</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/11-streaming)

[<span class="lesson-number">12</span><span class="lesson-copy"><strong>模型接入</strong><span>保留默认无 Key 路径，按需接入模型</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/12-model)

[<span class="lesson-number">13</span><span class="lesson-copy"><strong>重试策略</strong><span>处理失败，限定重试范围与次数</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/13-retry)

[<span class="lesson-number">14</span><span class="lesson-copy"><strong>幂等控制</strong><span>避免重复执行带来重复副作用</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/14-idempotency)

[<span class="lesson-number">15</span><span class="lesson-copy"><strong>可观测性</strong><span>沿执行路径定位状态与问题</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/15-observability)

</div>
</section>

<section class="curriculum-group" aria-labelledby="practice">
<div class="group-heading">
<p class="group-index">16—18 <span>/ IN PRACTICE</span></p>

### 实战 · 串起业务闭环 {#practice}

<p>把机制放回业务语境。<br>业务写入为模拟，不连接生产系统。</p>
</div>
<div class="lesson-grid">

[<span class="lesson-number">16</span><span class="lesson-copy"><strong>工单处理</strong><span>从分类、路由到处理结果</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/16-ticket)

[<span class="lesson-number">17</span><span class="lesson-copy"><strong>费用审批</strong><span>组合规则判断与人工审核</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/17-expense)

[<span class="lesson-number">18</span><span class="lesson-copy"><strong>研究工作流</strong><span>组织多阶段处理与结果汇总</span></span><span class="lesson-arrow" aria-hidden="true">→</span>](/lessons/18-research)

</div>
</section>
</section>

<section class="learning-notes" aria-labelledby="before-you-start">
<div class="notes-intro">
<p class="eyebrow">READ THIS FIRST</p>

## 在开始之前 {#before-you-start}

<p>知道它做什么，<br>也知道它不做什么。</p>

[阅读能力与边界 →](/guide/boundaries)

</div>
<div class="notes-content">
<div class="learning-note">
<span class="note-number" aria-hidden="true">01</span>
<div>

### 先跑确定性节点，再接模型

默认无 Key，使用纯 Java 确定性节点。真实运行的是 StateGraph 编排引擎，不是用流程图替代执行。DashScope 需配置环境变量，具体方式见[模型接入](/lessons/12-model)。

</div>
</div>
<div class="learning-note">
<span class="note-number" aria-hidden="true">02</span>
<div>

### 文档在线阅读，Java 本地启动

GitHub Pages 只展示静态文档，不托管 Java 后端，也不能在线执行 Demo。请按[开始学习](/guide/getting-started)在本地启动 Java 服务，再阅读[运行与调试](/guide/runtime)。

</div>
</div>
<div class="learning-note">
<span class="note-number" aria-hidden="true">03</span>
<div>

### 教学闭环，不冒充生产系统

业务写入是模拟，示例不意味着已经具备生产级持久化、外部业务集成或安全保障。检查点、执行记录与幂等记录保存在当前进程内，重启后不会保留。

</div>
</div>
</div>
</section>

<nav class="guide-links" aria-label="学习辅助指南">
<span>需要一份参照？</span>

[核心概念 →](/guide/concepts)

[运行与调试 →](/guide/runtime)

[常见问题 →](/guide/troubleshooting)

</nav>

</div>
