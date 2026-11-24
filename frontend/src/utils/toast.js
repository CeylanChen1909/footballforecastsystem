/**
 * 轻量 toast（替代旧版 Element Plus ElMessage）。
 * store 与视图分离：api / stores 只调 pushToast，ToastViewport 负责渲染。
 */
import { createSignal } from 'solid-js'

const [toasts, setToasts] = createSignal([])
let nextId = 1

export function pushToast(type, message, durationMs = 4000) {
  const text = String(message || '').trim()
  if (!text) return null
  const id = nextId++
  setToasts((list) => [...list.slice(-3), { id, type: type || 'info', message: text }])
  window.setTimeout(() => dismissToast(id), durationMs)
  return id
}

export function dismissToast(id) {
  setToasts((list) => list.filter((item) => item.id !== id))
}

export const toastSuccess = (message) => pushToast('success', message)
export const toastError = (message) => pushToast('error', message)
export const toastWarning = (message) => pushToast('warning', message)
export const toastInfo = (message) => pushToast('info', message)

export { toasts }
export default { toasts, pushToast, dismissToast, toastSuccess, toastError, toastWarning, toastInfo }
