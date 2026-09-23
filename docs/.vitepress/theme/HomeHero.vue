<script setup lang="ts">
import { withBase } from 'vitepress'
</script>

<template>
  <section class="graph-hero" aria-labelledby="hero-title">
    <div class="hero-copy">
      <p class="eyebrow"><span class="eyebrow-line" aria-hidden="true"></span> SPRING AI ALIBABA / GRAPH</p>
      <h1 id="hero-title">把流程，<br><span>写成一张图。</span></h1>
      <p class="hero-subtitle">从第一个节点，到完整业务工作流。</p>
      <p class="hero-description">18 个真实 StateGraph Demo，沿着状态、分支与执行路径，逐步理解 Graph 编排。不是概念图解，是可以在本地运行的 Java 示例。</p>
      <div class="hero-actions">
        <a class="action-link action-primary" :href="withBase('/guide/getting-started')">开始学习 <span aria-hidden="true">→</span></a>
        <a class="action-link action-secondary" href="https://github.com/ibqy/spring-ai-alibaba-graph-demo" target="_blank" rel="noopener noreferrer">查看源码 <span aria-hidden="true">↗</span><span class="sr-only">（新窗口）</span></a>
      </div>
      <p class="hero-note">默认无 Key · 纯 Java 确定性节点 · 本地运行</p>
    </div>

    <figure class="graph-figure">
      <div class="graph-figure-heading"><span>一条路径，读懂编排</span><span class="figure-index">FIG. 01</span></div>
      <svg class="graph-diagram" viewBox="0 0 520 438" role="img" aria-labelledby="graph-title graph-description">
        <title id="graph-title">StateGraph 条件分支结构示意</title>
        <desc id="graph-description">从 START 进入状态节点，由条件路由选择默认的纯 Java 确定性节点，或需要环境变量的可选模型节点，最后汇合到 END。此图是结构示意，不是在线执行结果。</desc>
        <defs>
          <marker id="graph-arrow-red" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 8 4 0 8Z" fill="var(--graph-red)" /></marker>
          <marker id="graph-arrow-muted" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 8 4 0 8Z" fill="var(--graph-muted)" /></marker>
        </defs>

        <g class="graph-guide" aria-hidden="true">
          <path d="M28 32h16M28 32v16M492 32h-16M492 32v16M28 410h16M28 410v-16M492 410h-16M492 410v-16" />
          <path d="M260 82v15M260 153v24M204 224H120v67M316 224h84v67M120 349v37h100M400 349v37H300" class="diagram-edge-base" />
        </g>
        <g class="graph-active-edges" fill="none" marker-end="url(#graph-arrow-red)">
          <path d="M260 74v24" />
          <path d="M260 154v27" />
          <path d="M204 224H120v66" />
          <path d="M120 350v36h99" />
        </g>
        <g class="graph-optional-edges" fill="none" marker-end="url(#graph-arrow-muted)">
          <path d="M316 224h84v66" />
          <path d="M400 350v36H301" />
        </g>

        <g class="graph-terminal">
          <rect x="219" y="40" width="82" height="34" />
          <text x="260" y="62">START</text>
        </g>
        <g class="graph-node">
          <rect x="165" y="102" width="190" height="52" />
          <text x="184" y="123" class="node-code">01 / STATE</text>
          <text x="184" y="144" class="node-name">输入写入状态</text>
        </g>
        <g class="graph-router">
          <path d="M260 182 316 224 260 266 204 224Z" />
          <text x="260" y="230">条件路由</text>
        </g>
        <text x="120" y="214" class="edge-label active-label">默认路径</text>
        <text x="400" y="214" class="edge-label">可选接入</text>
        <g class="graph-node graph-node-active">
          <rect x="28" y="294" width="184" height="56" />
          <text x="44" y="316" class="node-code">02 / JAVA</text>
          <text x="44" y="337" class="node-name">确定性节点 · 无 Key</text>
        </g>
        <g class="graph-node graph-node-optional">
          <rect x="308" y="294" width="184" height="56" />
          <text x="324" y="316" class="node-code">02 / MODEL</text>
          <text x="324" y="337" class="node-name">DashScope · 需 Key</text>
        </g>
        <g class="graph-terminal">
          <rect x="223" y="369" width="74" height="34" />
          <text x="260" y="391">END</text>
        </g>
      </svg>
      <figcaption><span class="graph-legend"><i aria-hidden="true"></i>默认执行路径</span><span>结构示意 · 非在线运行结果</span></figcaption>
    </figure>
  </section>
</template>
