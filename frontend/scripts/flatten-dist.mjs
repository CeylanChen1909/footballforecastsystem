/**
 * flatten-dist.mjs — 把 start mode 的构建产物拷平到 dist/（Plan B）。
 *
 * 背景：@solidjs/vite-plugin 3.0.1 的 start mode 在 config 钩子里把
 * environments.client.build.outDir 硬编码为 `dist/client`（见
 * node_modules/@solidjs/vite-plugin/dist/esm/index.mjs），用户在
 * vite.config.js 里写 environments.client.build.outDir: 'dist' 也会被
 * 插件钩子覆盖（2026-11-23 实测：产物仍进 dist/client）——Plan A 已证实无效。
 * 契约 §7.1 要求产物是 dist/ + dist/index.html（Dockerfile 原样拷 dist），
 * 所以构建后由本脚本把 dist/client/* 拷到 dist/ 根，并删掉 dist/client
 * 与 start mode 附带的 dist/server（SSR 入口，nginx 静态托管用不到）。
 *
 * 用法：npm run build（= vite build && node scripts/flatten-dist.mjs）。
 * 幂等：dist/client 不存在时直接跳过（例如将来插件改了默认输出）。
 */
import { cpSync, existsSync, readdirSync, rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const dist = join(root, 'dist')
const client = join(dist, 'client')
const server = join(dist, 'server')

if (!existsSync(client)) {
  console.log('[flatten-dist] dist/client 不存在，跳过（可能已是 dist 直出）')
  process.exit(0)
}
for (const entry of readdirSync(client)) {
  cpSync(join(client, entry), join(dist, entry), { recursive: true })
}
rmSync(client, { recursive: true, force: true })
rmSync(server, { recursive: true, force: true })
console.log('[flatten-dist] dist/client 已拷平到 dist/，dist/server 已删')
