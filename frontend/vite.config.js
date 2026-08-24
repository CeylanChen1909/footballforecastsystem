import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    target: 'es2020',
    cssCodeSplit: true,
    chunkSizeWarningLimit: 700,
    minify: 'esbuild',
    esbuild: { drop: ['debugger'] },
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          const normalized = id.replaceAll('\\\\', '/')
          if (normalized.includes('/echarts')) return 'echarts'
          if (normalized.includes('/@element-plus/icons-vue')) return 'element-plus-icons'
          if (normalized.includes('/element-plus/es/components/')) {
            const match = normalized.match(/\/element-plus\/es\/components\/([^/]+)/)
            const heavy = new Set(['table', 'table-v2', 'date-picker', 'date-picker-panel', 'dialog', 'drawer', 'menu', 'tree-v2', 'upload'])
            if (match && heavy.has(match[1])) return `element-${match[1]}`
          }
          if (normalized.includes('/element-plus')) return 'element-plus-core'
          if (normalized.includes('/vue/') || normalized.includes('/vue-router') || normalized.includes('/pinia')) return 'vue-vendor'
          if (id.includes('axios')) return 'http'
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
