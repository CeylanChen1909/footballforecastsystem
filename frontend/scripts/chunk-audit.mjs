import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const assets = path.join(root, 'dist', 'assets')
if (!fs.existsSync(assets)) {
  console.log('Chunk audit skipped: run npm run build first.')
  process.exit(0)
}

const files = fs.readdirSync(assets).filter(name => /\.(js|css)$/.test(name))
const sizes = files.map(name => ({ name, bytes: fs.statSync(path.join(assets, name)).size }))
  .sort((a, b) => b.bytes - a.bytes)

const totalJs = sizes.filter(item => item.name.endsWith('.js')).reduce((sum, item) => sum + item.bytes, 0)
const matches = sizes.filter(item => /^Matches-/i.test(item.name))
const entryish = sizes.filter(item => /^(index-|vue-vendor-|element-plus-core-)/i.test(item.name))

console.log('=== ChenFootball chunk audit (matches/home LCP) ===')
console.log(`assets: ${files.length}, total JS: ${(totalJs / 1024).toFixed(1)} KB`)
console.log('top 8:')
for (const item of sizes.slice(0, 8)) {
  console.log(`  ${(item.bytes / 1024).toFixed(1).padStart(8)} KB  ${item.name}`)
}
console.log('matches route chunks:')
for (const item of matches) console.log(`  ${(item.bytes / 1024).toFixed(1).padStart(8)} KB  ${item.name}`)
console.log('shell / vendor:')
for (const item of entryish) console.log(`  ${(item.bytes / 1024).toFixed(1).padStart(8)} KB  ${item.name}`)
console.log('Expect Matches JS < ~80KB gzip-agnostic raw; focus rail should be a separate async chunk.')
