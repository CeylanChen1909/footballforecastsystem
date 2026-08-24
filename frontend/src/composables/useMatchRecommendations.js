import { computed, ref } from 'vue'
import { crawlerApi } from '../api'

const cacheKey = () => 'match-focus:rolling'

const readCache = () => {
  try {
    const value = JSON.parse(sessionStorage.getItem(cacheKey()) || 'null')
    return value && Array.isArray(value.items) ? value : null
  } catch {
    return null
  }
}

const writeCache = value => {
  try { sessionStorage.setItem(cacheKey(), JSON.stringify({ ...value, savedAt: Date.now() })) } catch { /* storage may be disabled */ }
}

export function useMatchRecommendations({ limit = 6 } = {}) {
  const items = ref([])
  const meta = ref({})
  const loading = ref(false)
  const error = ref('')
  const stale = ref(false)
  let requestSerial = 0

  const hasItems = computed(() => items.value.length > 0)
  const lastUpdated = computed(() => meta.value?.generatedAt || '')

  const apply = (payload, { isStale = false } = {}) => {
    const nextItems = Array.isArray(payload?.items)
      ? payload.items
      : Array.isArray(payload?.response) ? payload.response : []
    items.value = nextItems
    meta.value = payload?.meta || payload || {}
    stale.value = isStale
  }

  const load = async (_date, { silent = false } = {}) => {
    const serial = ++requestSerial
    const cached = readCache()
    if (cached && !items.value.length) apply(cached, { isStale: true })
    if (!silent) loading.value = true
    error.value = ''
    try {
      const response = await crawlerApi.getRecommendations({ mode: 'focus', limit })
      if (serial !== requestSerial) return
      apply(response)
      writeCache(response)
    } catch (requestError) {
      if (serial !== requestSerial) return
      if (cached) {
        apply(cached, { isStale: true })
        error.value = '推荐数据暂时无法更新，当前显示上次成功读取的结果。'
      } else {
        items.value = []
        meta.value = {}
        error.value = requestError?.message || '比赛焦点暂时无法更新，请稍后重试。'
      }
    } finally {
      if (serial === requestSerial) loading.value = false
    }
  }

  const retry = date => load(date)

  return { items, meta, loading, error, stale, hasItems, lastUpdated, load, retry }
}
