import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    target: 'es2020',
    cssCodeSplit: true,
    assetsInlineLimit: 4096,
    chunkSizeWarningLimit: 700,
    minify: 'esbuild',
    esbuild: { drop: ['debugger'] },
    modulePreload: {
      polyfill: true,
      resolveDependencies(filename, deps) {
        return deps.filter((dep) => {
          const d = String(dep).replace(/\\/g, '/')
          if (/(admin-app|agent-app|prediction-app|vite-runtime)/i.test(d)) return false
          if (/element-(table|table-v2|date-picker|dialog|drawer|menu|tree-v2|upload)/i.test(d)) return false
          if (/echarts/i.test(d)) return false
          return true
        })
      }
    },
    rollupOptions: {
      output: {
        manualChunks(id) {
          const normalized = id.replace(/\\/g, '/')
          // Vite injects \0vite/preload-helper into every dynamic-import chunk.
          // If it lands in admin-app, the entry statically imports admin-app and
          // the user shell preloads admin CSS. Keep it in a tiny dedicated chunk.
          if (
            id.includes('\0') ||
            normalized.includes('vite/preload-helper') ||
            normalized.includes('vite/modulepreload-polyfill') ||
            normalized.includes('commonjsHelpers') ||
            /\/node_modules\/vite\//.test(normalized)
          ) {
            return 'vite-runtime'
          }
          if (normalized.includes('/src/plugins/register-element-plus-admin') ||
              normalized.includes('/src/styles/element-plus-admin-on-demand') ||
              normalized.includes('/src/plugins/element-plus-admin-extras')) {
            return 'admin-app'
          }
          if (normalized.includes('/src/views/admin/') || normalized.includes('/src/components/charts/')) {
            return 'admin-app'
          }
          // Keep stores/api/utils out of feature chunks so the entry never
          // statically imports agent-app / admin-app for shared helpers.
          if (
            normalized.includes('/src/stores/') ||
            normalized.includes('/src/api/') ||
            normalized.includes('/src/utils/')
          ) {
            return 'app-shared'
          }
          // Agent.vue only in agent-app. AgentLauncher is under components/layout and stays
          // a tiny async chunk so App.vue never pulls the Agent shell on first paint.
          if (normalized.includes('/src/views/user/Agent.vue')) {
            return 'agent-app'
          }
          if (normalized.includes('/src/views/user/Prediction.vue') || normalized.includes('/src/components/prediction/')) {
            return 'prediction-app'
          }
          if (normalized.includes('/src/components/matches/PredictionDiscovery.vue')) {
            return 'matches-discovery'
          }
          if (!id.includes('node_modules')) return undefined
          if (normalized.includes('/echarts')) return 'echarts'
          if (normalized.includes('/@element-plus/icons-vue')) return 'element-plus-icons'
          if (normalized.includes('/element-plus/es/components/')) {
            const match = normalized.match(/\/element-plus\/es\/components\/([^/]+)/)
            const heavy = new Set(['table', 'table-v2', 'date-picker', 'date-picker-panel', 'dialog', 'drawer', 'menu', 'tree-v2', 'upload'])
            if (match && heavy.has(match[1])) return `element-${match[1]}`
          }
          if (normalized.includes('/element-plus')) return 'element-plus-core'
          if (normalized.includes('/vue/') || normalized.includes('/@vue/') || normalized.includes('/vue-router') || normalized.includes('/pinia')) {
            return 'vue-vendor'
          }
          if (normalized.includes('/axios')) return 'http'
          return undefined
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api/news': { target: 'http://localhost:8082', changeOrigin: true },
      '/api': { target: 'http://localhost:8082', changeOrigin: true },
      '/ml': {
        target: 'http://localhost:5001',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ml/, '')
      }
    }
  }
})
