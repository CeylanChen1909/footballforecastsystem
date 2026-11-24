import { defineConfig } from 'vite'
import solid from '@solidjs/vite-plugin'

/**
 * ChenFootball · SolidJS 2.0 脚手架
 *
 * start mode（SPA，客户端渲染，不做 SSR）：
 *  - 官方文档（docs.solidjs.com/reference/vite-plugin-solid/start）：
 *    `solid({ start: true })` 单独使用即 SPA；dev 以流式文档壳挂载应用，
 *    `vite build` 输出纯静态文件到 `dist/client`，`vite preview` 自带 SPA fallback。
 *  - start mode 下没有 index.html / 入口文件：文档壳是 `src/Document.jsx`，
 *    应用入口由插件生成（挂载 `src/App.jsx` 默认导出）。不要再补 index.html
 *    或 src/main.jsx，插件会忽略或与之冲突。
 *  - 部署位（契约 §7.1）：旧版 Dockerfile 拷 `dist`，nginx.conf 拷的是
 *    `/usr/share/nginx/html` 下的 `dist` 内容；start mode 默认写
 *    `dist/client`，还附带一个 `dist/server`（SSR 入口）。Plan A（用户
 *    environments.client.build.outDir: 'dist'）已证实无效——插件在 config
 *    钩子里硬编码了 dist/client，会覆盖用户配置（2026-11-23 实测）。
 *    采用 Plan B：`npm run build` = `vite build && node
 *    scripts/flatten-dist.mjs`，构建后把 dist/client 拷平到 dist/ 根并删
 *    dist/client 与 dist/server；Dockerfile 与 nginx.conf 不用改。
 */
export default defineConfig({
  plugins: [solid({ start: true })],
  build: {
    target: 'es2020',
    cssCodeSplit: true,
    assetsInlineLimit: 4096,
    chunkSizeWarningLimit: 700,
    // Vite 8 默认用 oxc 压缩（minify 默认值）；不要写 minify: 'esbuild'，
    // 否则构建会要求另装 esbuild（Vite 8 已弃用 esbuild 压缩路径）。
    modulePreload: { polyfill: true },
  },
  server: {
    port: 5173,
    // 与旧版 vite.config.js 一致：本地开发时代理到网关。
    // /ml 代理已删：契约 §2.8 确认它零调用方（旧版有代理、前端却无请求）。
    proxy: {
      '/api/news': { target: 'http://localhost:8082', changeOrigin: true },
      '/api': { target: 'http://localhost:8082', changeOrigin: true },
    },
  },
  preview: {
    port: 4173,
  },
})
