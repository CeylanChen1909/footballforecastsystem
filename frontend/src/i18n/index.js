import { computed, ref } from 'vue'
import en from './locales/en.js'
import zh from './locales/zh.js'

export const SUPPORTED_LOCALES = ['en', 'zh']
export const DEFAULT_LOCALE = 'en'
export const LOCALE_STORAGE_KEY = 'cf_locale'

const extraModules = import.meta.glob('./locales/extra/*.js', { eager: true })
const mergedEn = { ...en }
const mergedZh = { ...zh }
for (const [path, mod] of Object.entries(extraModules)) {
  const target = path.endsWith('.en.js') ? mergedEn : path.endsWith('.zh.js') ? mergedZh : null
  if (target) Object.assign(target, mod.default || {})
}
const messages = { en: mergedEn, zh: mergedZh }

const normalizeLocale = (value) => {
  const raw = String(value || '').trim().toLowerCase()
  if (!raw) return ''
  if (raw === 'zh' || raw.startsWith('zh-')) return 'zh'
  if (raw === 'en' || raw.startsWith('en-')) return 'en'
  return ''
}

const readUrlLocale = () => {
  if (typeof window === 'undefined') return ''
  try {
    return normalizeLocale(new URLSearchParams(window.location.search).get('lang'))
  } catch {
    return ''
  }
}

const readStoredLocale = () => {
  if (typeof window === 'undefined') return ''
  try {
    return normalizeLocale(window.localStorage.getItem(LOCALE_STORAGE_KEY))
  } catch {
    return ''
  }
}

const persistLocale = (value) => {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, value)
  } catch {
    /* Storage may be unavailable (private mode, quota, embedded webview). */
  }
}

const applyDocumentLocale = (value) => {
  if (typeof document === 'undefined') return
  document.documentElement.lang = value
}

const initialLocale = readUrlLocale() || readStoredLocale() || DEFAULT_LOCALE

/** Reactive current locale. Read it in templates/computed values to re-render. */
export const locale = ref(initialLocale)
const isZh = computed(() => locale.value === 'zh')

applyDocumentLocale(initialLocale)
// A shared ?lang=zh|en link is an explicit choice; remember it for the next visit.
if (readUrlLocale()) persistLocale(initialLocale)

/**
 * Translate a key for the active locale.
 * Supports `{name}` interpolation: t('hello', { name: 'Chen' }).
 */
export function t(key, params = {}) {
  if (key == null) return ''
  const dict = messages[locale.value] || messages[DEFAULT_LOCALE]
  const value = dict[key] ?? messages[DEFAULT_LOCALE][key] ?? key
  if (typeof value !== 'string') return value
  if (!params || typeof params !== 'object') return value
  return value.replace(/\{(\w+)\}/g, (match, name) => (
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : match
  ))
}

export function setLocale(value) {
  const next = normalizeLocale(value) || DEFAULT_LOCALE
  locale.value = next
  applyDocumentLocale(next)
  persistLocale(next)
  return next
}

export function useI18n() {
  return { locale, isZh, t, setLocale }
}

export default { locale, isZh, t, setLocale, useI18n, SUPPORTED_LOCALES, DEFAULT_LOCALE }
