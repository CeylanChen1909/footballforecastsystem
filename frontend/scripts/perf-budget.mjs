import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const dist = path.join(root, 'dist')
const assets = path.join(dist, 'assets')
if (!fs.existsSync(assets)) {
  console.log('Performance budget skipped: run npm run build first.')
  process.exit(0)
}

const files = fs.readdirSync(assets).filter(name => /\.(js|css)$/.test(name))
const sizes = files.map(name => ({ name, bytes: fs.statSync(path.join(assets, name)).size }))
const largest = [...sizes].sort((a, b) => b.bytes - a.bytes)[0]
const totalJs = sizes.filter(item => item.name.endsWith('.js')).reduce((sum, item) => sum + item.bytes, 0)
assert(largest.bytes <= 1_250_000, `largest static asset exceeds 1.25 MB: ${largest.name} (${largest.bytes})`)
assert(totalJs <= 4_000_000, `total JS exceeds 4 MB: ${totalJs}`)
const html = fs.readFileSync(path.join(dist, 'index.html'), 'utf8')
assert(/<meta[^>]+name=["']viewport["']/i.test(html), 'viewport meta is missing')
assert(/<title>[^<]+<\/title>/i.test(html), 'document title is missing')
console.log(`Performance budget passed: ${files.length} assets, JS ${totalJs} bytes, largest ${largest.name} (${largest.bytes})`)
