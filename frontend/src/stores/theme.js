/**
 * 明暗主题（第一档：tokens 切换）。
 *
 * 机制与旧版设计报告 §6 一致：`document.documentElement.dataset.theme = 'dark'`
 * 即启用 tokens 文件里的 [data-theme="dark"] 块；切换时同步改 theme-color。
 * - 偏好存 localStorage `football_theme`：'light' | 'dark' | 'system'（默认 system）。
 * - 默认跟随系统 prefers-color-scheme；用户手动切换后写入明确偏好。
 * - 首屏防闪白不靠本模块：Document.jsx <head> 里的内联脚本在 CSS/JS 之前
 *   用同一 key 与同一色值先定好 data-theme，本模块只负责运行时的响应式切换。
 */
import { createSignal } from 'solid-js'

export const THEME_STORAGE_KEY = 'football_theme'
export const THEME_COLOR_LIGHT = '#f7f8f7'
export const THEME_COLOR_DARK = '#0f1512'

function readPreference() {
  try {
    const value = window.localStorage.getItem(THEME_STORAGE_KEY)
    if (value === 'light' || value === 'dark' || value === 'system') return value
  } catch {
    /* ignore */
  }
  return 'system'
}

function systemIsDark() {
  try {
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  } catch {
    return false
  }
}

export function resolveTheme(preference) {
  if (preference === 'light') return 'light'
  if (preference === 'dark') return 'dark'
  return systemIsDark() ? 'dark' : 'light'
}

const [preference, setPreferenceSignal] = createSignal(readPreference())
const [theme, setThemeSignal] = createSignal(resolveTheme(readPreference()))

function applyTheme(next) {
  document.documentElement.setAttribute('data-theme', next)
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', next === 'dark' ? THEME_COLOR_DARK : THEME_COLOR_LIGHT)
  setThemeSignal(next)
}

export function setPreference(next) {
  const value = next === 'light' || next === 'dark' ? next : 'system'
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, value)
  } catch {
    /* ignore */
  }
  setPreferenceSignal(value)
  applyTheme(resolveTheme(value))
}

/** 手动切换：亮 ↔ 暗（写入明确偏好，不再跟随系统）。 */
export function toggleTheme() {
  setPreference(theme() === 'dark' ? 'light' : 'dark')
}

export function resetThemeToSystem() {
  setPreference('system')
}

/** App 启动时调用一次：与 Document 内联脚本对齐，并监听系统偏好变化。 */
export function initTheme() {
  applyTheme(resolveTheme(preference()))
  try {
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => {
      if (preference() === 'system') applyTheme(resolveTheme('system'))
    }
    if (typeof media.addEventListener === 'function') media.addEventListener('change', onChange)
    else if (typeof media.addListener === 'function') media.addListener(onChange)
  } catch {
    /* ignore */
  }
}

export { preference, theme }
export default { preference, theme, setPreference, toggleTheme, resetThemeToSystem, initTheme, resolveTheme }
