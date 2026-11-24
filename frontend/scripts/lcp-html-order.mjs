/**
 * lcp-html-order.mjs — Solid 适配版（原 Vue 版见 frontend-r11/scripts/）。
 *
 * Vue 版的任务：从 index.html 剔掉路由独占 CSS（admin-app / agent-app /
 * prediction-app）并把 index.css / element-plus-core 排前面。
 *
 * Solid 侧的产物形态不同（实测 dist/index.html）：
 *  - index.html 里只有一张样式表：virtual_solid-ssr-entry-client-*.css
 *   （Document.jsx 的关键壳 CSS + 全局 tokens；路由页 CSS 以
 *    Agent-*.css / AdminDashboard-*.css 等 lazy chunk 形态存在，由 JS
 *    动态加载，根本不在 index.html 里出现）。
 *  - modulepreload 也只有 web / api / privacyConsent 三个共享 chunk，
 *    没有路由 chunk。
 * 所以“剔路由 CSS / 剔路由 modulepreload”在 Solid 侧是 no-op：本脚本
 * 保留 Vue 版的检测与排序逻辑（路由 chunk 名按 Solid 的 chunk 名改写，
 * 万一将来某次构建把路由 CSS 链进 index.html 也能兜住），实测会输出
 * stripped 0；关键壳 CSS 永远排第一。
 *
 * 用法：npm run build（= vite build && flatten-dist && lcp-html-order &&
 * prerender-meta）。幂等：无 dist/index.html 时直接跳过。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const distDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'dist')
const indexPath = path.join(distDir, 'index.html')
if (!fs.existsSync(indexPath)) {
  console.log('lcp-html-order: no dist/index.html')
  process.exit(0)
}

let html = fs.readFileSync(indexPath, 'utf8')
const linkRe = /<link[^>]+rel=["']stylesheet["'][^>]*>/gi
const links = html.match(linkRe) || []
if (!links.length) {
  console.log('lcp-html-order: no stylesheets')
  process.exit(0)
}

// Solid 路由 chunk 的 CSS 文件名（lazy chunk 由 JS 动态加载，正常不在
// index.html 里；这里只做兜底检测）。
const ROUTE_CSS_RE = /(AdminDashboard|Agent|Prediction|Matches|CompetitionHub|TeamSquad|Profile|NotFound|About|Privacy|Terms|ChangelogButton|ConfirmDialog|Modal|PageSection|Placeholder|page-common|legal|competitions|squad|agent|profile|admin|changelog|notfound)-[A-Za-z0-9_-]+\.css/i
const isRouteCss = (tag) => ROUTE_CSS_RE.test(tag)

const rank = (tag) => {
  const href = (tag.match(/href=["']([^"']+)["']/) || [])[1] || ''
  // 关键壳 CSS（Document.jsx 的 CRITICAL_CSS + 全局样式入口）永远第一。
  if (/virtual_solid-ssr-entry-client/i.test(href)) return 0
  if (isRouteCss(tag)) return 9
  return 5
}

// Drop route-only CSS from the user shell HTML. Async chunks load their own CSS.
const kept = links.filter((tag) => !isRouteCss(tag))
const ordered = [...kept].sort((a, b) => rank(a) - rank(b))
const stripped = links.length - kept.length

html = html.replace(linkRe, '')
const block = ordered.join('\n    ')
if (/id="critical-shell"/.test(html)) {
  html = html.replace(/<\/style>/i, `</style>\n    ${block}`)
} else {
  html = html.replace(/<\/head>/i, `    ${block}\n  </head>`)
}

html = html.replace(
  /\s*<link[^>]+rel=["']modulepreload["'][^>]*(?:AdminDashboard|Agent|Prediction|Matches|CompetitionHub|TeamSquad|Profile|NotFound|About|Privacy|Terms)[^>]*>\s*/gi,
  '\n    ',
)

// Canonical PWA manifest only.
html = html.replace(/\s*<link[^>]+rel=["']manifest["'][^>]*href=["']\/manifest\.webmanifest["'][^>]*>\s*/gi, '\n    ')
if (!/rel=["']manifest["'][^>]*href=["']\/site\.webmanifest["']/i.test(html)) {
  html = html.replace(/<link rel="apple-touch-icon"[^>]*>/i, (m) => `${m}\n    <link rel="manifest" href="/site.webmanifest" />`)
}

fs.writeFileSync(indexPath, html)
console.log(`lcp-html-order: kept ${ordered.length} stylesheets, stripped ${stripped} route CSS`)
