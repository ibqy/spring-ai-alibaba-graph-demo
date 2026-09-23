import type { Theme } from 'vitepress'
import DefaultTheme from 'vitepress/theme'
import HomeHero from './HomeHero.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    app.component('HomeHero', HomeHero)
  }
} satisfies Theme
