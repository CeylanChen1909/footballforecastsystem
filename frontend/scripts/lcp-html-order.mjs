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

const isRouteCss = (tag) => /(admin-app|agent-app|prediction-app)/i.test(tag)

const rank = (tag) => {
  const href = (tag.match(/href=["']([^"']+)["']/) || [])[1] || ''
  if (/\/index-[^/"']+\.css/i.test(href)) return 0
  if (/element-plus-core/i.test(href)) return 1
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
  /\s*<link[^>]+rel=["']modulepreload["'][^>]*(?:admin-app|agent-app|prediction-app)[^>]*>\s*/gi,
  '\n    '
)

// Canonical PWA manifest only.
html = html.replace(/\s*<link[^>]+rel=["']manifest["'][^>]*href=["']\/manifest\.webmanifest["'][^>]*>\s*/gi, '\n    ')
if (!/rel=["']manifest["'][^>]*href=["']\/site\.webmanifest["']/i.test(html)) {
  html = html.replace(/<link rel="apple-touch-icon"[^>]*>/i, (m) => `${m}\n    <link rel="manifest" href="/site.webmanifest" />`)
}

fs.writeFileSync(indexPath, html)
console.log(`lcp-html-order: kept ${ordered.length} stylesheets, stripped ${stripped} route CSS`)
