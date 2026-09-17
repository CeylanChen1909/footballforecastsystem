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
        // Keep matches/home LCP lean: skip admin and heavy optional EP widgets.
        return deps.filter((dep) => {
          const d = String(dep).replace(/\\/g, '/')
          if (/(admin-app|agent-app|prediction-app)/i.test(d)) return false
          if (/element-(table|table-v2|date-picker|drawer|menu|tree-v2|upload)/i.test(d)) return false
          if (/echarts/i.test(d)) return false
          return true
        })
      }
    },
    rollupOptions: {
      output: {
        manualChunks(id) {
          const normalized = id.replace(/\\/g, '/')
          if (normalized.includes('/src/views/admin/') || normalized.includes('/src/components/charts/')) {
            return 'admin-app'
          }
          // Keep heavy user surfaces out of the matches/home LCP path.
          if (normalized.includes('/src/views/user/Agent.vue') || normalized.includes('/src/components/agent/')) {
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
          if (normalized.includes('/vue/') || normalized.includes('/@vue/') || normalized.includes('/vue-router') || normalized.includes('/pinia')) return 'vue-vendor'
          if (normalized.includes('/axios')) return 'http'
          return undefined
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api/news': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/ml': {
        target: 'http://localhost:5001',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ml/, ''),
      },
    }
  }
})
