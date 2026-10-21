import { defineConfig } from 'vitepress'

const repository = 'https://github.com/ibqy/spring-ai-alibaba-graph-demo'

export default defineConfig({
  lang: 'zh-CN',
  title: 'Graph 学习实战',
  titleTemplate: ':title · Spring AI Alibaba',
  description:
    '18 个真实 Spring AI Alibaba StateGraph Demo：从基础编排到业务实战。Java 21、Spring Boot 3.5.8、StateGraph 1.1.2.2，默认无 Key 本地运行。',
  base: '/spring-ai-alibaba-graph-demo/',
  cleanUrls: true,
  appearance: true,
  head: [
    ['meta', { name: 'theme-color', content: '#ffffff', media: '(prefers-color-scheme: light)' }],
    ['meta', { name: 'theme-color', content: '#151515', media: '(prefers-color-scheme: dark)' }],
    ['link', {
      rel: 'icon',
      type: 'image/svg+xml',
      href: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"%3E%3Cpath fill="%23E60012" d="M0 0h32v32H0z"/%3E%3Cpath fill="none" stroke="white" stroke-width="2" d="M9 9h14v14H9V9m0 0 14 14"/%3E%3C/svg%3E'
    }]
  ],
  markdown: {
    lineNumbers: true,
    theme: { light: 'github-light', dark: 'github-dark' }
  },
  themeConfig: {
    siteTitle: 'GRAPH / 学习实战',
    logo: {
      src: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"%3E%3Cpath fill="%23E60012" d="M0 0h32v32H0z"/%3E%3Cpath fill="none" stroke="white" stroke-width="2" d="M9 9h14v14H9V9m0 0 14 14"/%3E%3C/svg%3E',
      alt: ''
    },
    nav: [
      { text: '课程地图', link: '/#curriculum', activeMatch: '^/lessons/' },
      { text: '学习指南', link: '/guide/getting-started', activeMatch: '^/guide/' },
      { text: '源代码', link: repository },
      { text: '学习路径', link: 'https://ibqy.github.io/curriculum' }
    ],
    sidebar: [
      {
        text: '学习指南',
        collapsed: false,
        items: [
          { text: '开始学习', link: '/guide/getting-started' },
          { text: '核心概念', link: '/guide/concepts' },
          { text: '运行与调试', link: '/guide/runtime' },
          { text: '常见问题', link: '/guide/troubleshooting' },
          { text: '能力与边界', link: '/guide/boundaries' }
        ]
      },
      {
        text: '01—06 / 基础 · 把图跑起来',
        collapsed: false,
        items: [
          { text: '01 · Hello Graph', link: '/lessons/01-hello' },
          { text: '02 · 顺序编排', link: '/lessons/02-sequence' },
          { text: '03 · 状态管理', link: '/lessons/03-state' },
          { text: '04 · 条件路由', link: '/lessons/04-routing' },
          { text: '05 · 循环控制', link: '/lessons/05-loop' },
          { text: '06 · 并行分支', link: '/lessons/06-parallel' }
        ]
      },
      {
        text: '07—15 / 高阶 · 让图可控',
        collapsed: false,
        items: [
          { text: '07 · 子图组合', link: '/lessons/07-subgraph' },
          { text: '08 · 检查点', link: '/lessons/08-checkpoint' },
          { text: '09 · 执行隔离', link: '/lessons/09-isolation' },
          { text: '10 · 人工审核', link: '/lessons/10-human-review' },
          { text: '11 · 流式输出', link: '/lessons/11-streaming' },
          { text: '12 · 模型接入', link: '/lessons/12-model' },
          { text: '13 · 重试策略', link: '/lessons/13-retry' },
          { text: '14 · 幂等控制', link: '/lessons/14-idempotency' },
          { text: '15 · 可观测性', link: '/lessons/15-observability' }
        ]
      },
      {
        text: '16—18 / 实战 · 串起业务闭环',
        collapsed: false,
        items: [
          { text: '16 · 工单处理', link: '/lessons/16-ticket' },
          { text: '17 · 费用审批', link: '/lessons/17-expense' },
          { text: '18 · 研究工作流', link: '/lessons/18-research' }
        ]
      }
    ],
    outline: { level: [2, 3], label: '本页目录' },
    sidebarMenuLabel: '课程目录',
    returnToTopLabel: '返回顶部',
    darkModeSwitchLabel: '外观',
    lightModeSwitchTitle: '切换至浅色模式',
    darkModeSwitchTitle: '切换至深色模式',
    skipToContentLabel: '跳转到内容',
    docFooter: { prev: '上一篇', next: '下一篇' },
    editLink: {
      pattern: `${repository}/edit/main/docs/:path`,
      text: '在 GitHub 查看 / 编辑本页源码'
    },
    search: {
      provider: 'local',
      options: {
        detailedView: true,
        miniSearch: {
          options: {
            // Segment Chinese prose as well as Java identifiers for local full-text search.
            tokenize: (text) => {
              const segmenter = new Intl.Segmenter('zh-CN', { granularity: 'word' })
              return Array.from(segmenter.segment(text))
                .filter((part) => part.isWordLike)
                .map((part) => part.segment)
            }
          },
          searchOptions: { combineWith: 'AND', fuzzy: false, prefix: true }
        },
        locales: {
          root: {
            translations: {
              button: { buttonText: '搜索文档', buttonAriaLabel: '搜索课程与指南' },
              modal: {
                displayDetails: '显示详细列表',
                resetButtonTitle: '清空搜索',
                backButtonTitle: '关闭搜索',
                noResultsText: '未找到相关内容',
                footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' }
              }
            }
          }
        }
      }
    },
    footer: {
      message: '学习示例，不是生产系统。GitHub Pages 只展示文档，Java 服务在本地启动。',
      copyright: 'MIT License · 2026 ibqy'
    }
  }
})
