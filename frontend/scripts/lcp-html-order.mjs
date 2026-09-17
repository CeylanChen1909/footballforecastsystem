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

const rank = (tag) => {
  const href = (tag.match(/href=["']([^"']+)["']/) || [])[1] || ''
  if (/\/index-[^/"']+\.css/i.test(href)) return 0
  if (/element-plus-core/i.test(href)) return 1
  if (/(admin-app|agent-app|prediction-app)/i.test(href)) return 9
  return 5
}

const ordered = [...links].sort((a, b) => rank(a) - rank(b))
const rewritten = ordered.map((tag) => {
  if (/(admin-app|agent-app|prediction-app)/i.test(tag) && !/media=/i.test(tag)) {
    return tag.replace(/\s*\/?>$/, ' media="print" onload="this.media=\'all\'" />')
  }
  return tag
})

html = html.replace(linkRe, '')
const block = rewritten.join('\n    ')
if (/id="critical-shell"/.test(html)) {
  html = html.replace(/<\/style>/i, `</style>\n    ${block}`)
} else {
  html = html.replace(/<\/head>/i, `    ${block}\n  </head>`)
}

html = html.replace(/\s*<link[^>]+rel=["']modulepreload["'][^>]*(?:admin-app|agent-app|prediction-app)[^>]*>\s*/gi, '\n    ')

fs.writeFileSync(indexPath, html)
console.log(`lcp-html-order: reordered ${rewritten.length} stylesheets`)
