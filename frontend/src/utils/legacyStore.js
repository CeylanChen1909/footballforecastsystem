/**
 * Solid 2.0 的 createStore setter 只有一个形态：setState(fn)，fn 收到
 * store 代理并直接变异它（produce 风格）。Solid 1.x 的路径形态
 * setState(key, value) / setState(index, key, value) / setState({...})
 * / setState([...]) 在 2.0 里会抛 "fn is not a function"
 * （2026-11-24 实测：Agent/Profile/Admin 的全部 createStore 调用点
 * 都是 1.x 形态，登录态 Agent 在 startNewSession 就崩）。
 *
 * 本 helper 在 2.0 setter 之上恢复 1.x 的调用形态，供迁移代码使用，
 * 语义与 1.x 一致：
 *  - set(value)            value 是数组 → 替换数组内容；对象 → 顶层合并
 *  - set((prev) => next)   以当前值为输入算出新值，再按上一条处理
 *  - set(key, value)       顶层字段赋值（value 是函数时为 updater）
 *  - set(index, {...})     数组第 index 项顶层合并
 *  - set(index, key, value) 数组第 index 项的字段赋值（value 可为 updater）
 */
import { createStore } from 'solid-js'

const replaceAll = (proxy, value) => {
  if (Array.isArray(value)) {
    proxy.splice(0, proxy.length, ...value)
    return
  }
  if (value && typeof value === 'object') {
    Object.assign(proxy, value)
  }
}

export function createLegacyStore(initialValue) {
  const [state, setState] = createStore(initialValue)
  const set = (...args) => {
    if (args.length === 1) {
      const value = args[0]
      if (typeof value === 'function') {
        setState((proxy) => replaceAll(proxy, value(proxy)))
      } else {
        setState((proxy) => replaceAll(proxy, value))
      }
      return
    }
    if (args.length === 2) {
      const [key, value] = args
      if (typeof key === 'number' && value && typeof value === 'object') {
        // set(index, {...patch})
        setState((proxy) => {
          if (proxy[key] != null) Object.assign(proxy[key], value)
        })
      } else {
        // set(key, value | updater)
        setState((proxy) => {
          proxy[key] = typeof value === 'function' ? value(proxy[key]) : value
        })
      }
      return
    }
    if (args.length === 3) {
      const [index, key, value] = args
      setState((proxy) => {
        const target = proxy[index]
        if (target == null) return
        target[key] = typeof value === 'function' ? value(target[key]) : value
      })
    }
  }
  return [state, set]
}

export default createLegacyStore
