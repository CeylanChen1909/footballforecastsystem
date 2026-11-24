/**
 * 焦点推荐 composable。
 *
 * 来源（2026-11-24 修复）：行为逐字取自 GitHub r11 原件
 * frontend/src/composables/useMatchRecommendations.js（68 行，SHA b4e50ee…）。
 * 契约 §10 称本文件“不存在”是错的——本地镜像漏拷所致。原件是 Vue
 * ref/computed，本文件改为 Solid createSignal/createMemo，其余语义不变。
 *
 * 原件行为（与重建版的差异，见 fix.md）：
 *  - 缓存键 'match-focus:rolling'（sessionStorage），无 TTL：
 *    savedAt 只写不读，缓存永不过期；命中缓存时仅在 items 为空时
 *    先显示并置 stale。
 *  - load(_date, { silent })：首参是日期占位（Vue 版未使用），不传
 *    keyword；调 crawlerApi.getRecommendations({ mode: 'focus', limit })。
 *  - 失败时有缓存则显示缓存 + 固定文案
 *    “推荐数据暂时无法更新，当前显示上次成功读取的结果。”；
 *    无缓存则清空 + requestError?.message 或固定文案
 *    “比赛焦点暂时无法更新，请稍后重试。”。
 *  - 返回 { items, meta, loading, error, stale, hasItems, lastUpdated,
 *    load, retry }；error 初始 ''（字符串，不是 Error 对象）。
 *
 * 用法（Matches.jsx）：useMatchRecommendations({ limit: 6 })，重命名为
 * hotMatches/hotMeta/hotLoading/hotError/hotStale/loadHotMatches。
 */
import { createMemo, createSignal } from 'solid-js'
import { crawlerApi } from '../api'

const CACHE_KEY = 'match-focus:rolling'

const readCache = () => {
  try {
    const value = JSON.parse(sessionStorage.getItem(CACHE_KEY) || 'null')
    return value && Array.isArray(value.items) ? value : null
  } catch {
    return null
  }
}

const writeCache = value => {
  try { sessionStorage.setItem(CACHE_KEY, JSON.stringify({ ...value, savedAt: Date.now() })) } catch { /* storage may be disabled */ }
}

export function useMatchRecommendations({ limit = 6 } = {}) {
  const [items, setItems] = createSignal([])
  const [meta, setMeta] = createSignal({})
  const [loading, setLoading] = createSignal(false)
  const [error, setError] = createSignal('')
  const [stale, setStale] = createSignal(false)
  let requestSerial = 0

  const hasItems = createMemo(() => items().length > 0)
  const lastUpdated = createMemo(() => meta()?.generatedAt || '')

  const apply = (payload, { isStale = false } = {}) => {
    const nextItems = Array.isArray(payload?.items)
      ? payload.items
      : Array.isArray(payload?.response) ? payload.response : []
    setItems(nextItems)
    setMeta(payload?.meta || payload || {})
    setStale(isStale)
  }

  const load = async (_date, { silent = false } = {}) => {
    const serial = ++requestSerial
    const cached = readCache()
    if (cached && !items().length) apply(cached, { isStale: true })
    if (!silent) setLoading(true)
    setError('')
    try {
      const response = await crawlerApi.getRecommendations({ mode: 'focus', limit })
      if (serial !== requestSerial) return
      apply(response)
      writeCache(response)
    } catch (requestError) {
      if (serial !== requestSerial) return
      if (cached) {
        apply(cached, { isStale: true })
        setError('推荐数据暂时无法更新，当前显示上次成功读取的结果。')
      } else {
        setItems([])
        setMeta({})
        setError(requestError?.message || '比赛焦点暂时无法更新，请稍后重试。')
      }
    } finally {
      if (serial === requestSerial) setLoading(false)
    }
  }

  const retry = date => load(date)

  return { items, meta, loading, error, stale, hasItems, lastUpdated, load, retry }
}

export default useMatchRecommendations
