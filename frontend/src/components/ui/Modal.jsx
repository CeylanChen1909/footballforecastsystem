/**
 * 轻量 Modal（替代 el-dialog；手写 overlay，与脚手架搜索/通知弹窗同款做法，
 * 不引入 Kobalte 以缩小 alpha 暴露面）。
 * props: open, title, onClose, children, wide（宽面板 820px，默认 600px）。
 * Esc 关闭、点击遮罩关闭；打开时锁定 body 滚动。
 */
import { createEffect, Show } from 'solid-js'
import './modal.css'

export default function Modal(props) {
  // Solid 2.0：createEffect 是双参（compute 追踪 props.open，effect 不追踪）；
  // effect 返回值即 cleanup（原 onCleanup 写法在 2.0 下不适用）。
  createEffect(
    () => props.open,
    (open) => {
      if (!open) return
      const onKey = (e) => {
        if (e.key === 'Escape') props.onClose?.()
      }
      document.addEventListener('keydown', onKey)
      const prevOverflow = document.body.style.overflow
      document.body.style.overflow = 'hidden'
      return () => {
        document.removeEventListener('keydown', onKey)
        document.body.style.overflow = prevOverflow
      }
    },
  )

  return (
    <Show when={props.open}>
      <div class="ff-modal-overlay" onClick={(e) => { if (e.target === e.currentTarget) props.onClose?.() }}>
        <div class={`ff-modal-panel ${props.wide ? 'is-wide' : ''}`} role="dialog" aria-modal="true" aria-label={props.title || ''}>
          <div class="ff-modal-head">
            <strong>{props.title}</strong>
            <button type="button" class="ff-modal-close" aria-label="关闭" onClick={() => props.onClose?.()}>
              ×
            </button>
          </div>
          <div class="ff-modal-body">{props.children}</div>
        </div>
      </div>
    </Show>
  )
}
