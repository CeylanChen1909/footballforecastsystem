/**
 * 共享确认弹窗（Kobalte Dialog 实现；替代 ElMessageBox.confirm / window.confirm）。
 *
 * 保留“取消”与“关闭”的区分（Vue ElMessageBox.confirm 的语义，window.confirm
 * 丢掉的那部分；样板页报告差异清单第 2 条）：
 *  - 点确认按钮 → resolve(true)
 *  - 点取消按钮 → resolve(false)
 *  - 点 × / 遮罩 / Esc 关闭 → resolve(null)
 * 调用方只有在返回 true 时才执行操作；false 与 null 都视为“未确认”。
 *
 * 用法（每页一次）：
 *   const { ask, ConfirmDialog } = createConfirm()
 *   const ok = await ask({ title: '取消关注', message: '确定取消关注吗？', confirmText: '取消关注', danger: true })
 *   if (!ok) return
 *   …并在 JSX 末尾渲染 <ConfirmDialog />
 */
import { createSignal } from 'solid-js'
import { Dialog } from '@kobalte/core/dialog'

export function createConfirm() {
  const [state, setState] = createSignal(null)
  let resolveFn = null

  const ask = (options) => {
    const opts = typeof options === 'string' ? { message: options } : options || {}
    return new Promise((resolve) => {
      resolveFn = resolve
      setState({
        title: opts.title || '请确认',
        message: opts.message || '',
        confirmText: opts.confirmText || '确认',
        cancelText: opts.cancelText || '取消',
        danger: Boolean(opts.danger),
      })
    })
  }

  const settle = (value) => {
    const resolve = resolveFn
    resolveFn = null
    setState(null)
    if (resolve) resolve(value)
  }

  function ConfirmDialog() {
    return (
      <Dialog
        open={state() !== null}
        onOpenChange={(open) => {
          if (!open) settle(null)
        }}
      >
        <Dialog.Portal>
          <Dialog.Overlay class="ff-dialog-overlay" />
          <Dialog.Content class="ff-dialog-content" aria-label={state()?.title || '请确认'}>
            <Dialog.Title class="ff-dialog-title">{state()?.title || '请确认'}</Dialog.Title>
            <Dialog.Description class="ff-dialog-desc">{state()?.message || ''}</Dialog.Description>
            <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:18px">
              <button type="button" class="ff-btn ff-btn-ghost" onClick={() => settle(false)}>
                {state()?.cancelText || '取消'}
              </button>
              <button
                type="button"
                class={`ff-btn ${state()?.danger ? 'ff-btn-danger' : 'ff-btn-primary'}`}
                onClick={() => settle(true)}
              >
                {state()?.confirmText || '确认'}
              </button>
            </div>
            <Dialog.CloseButton class="ff-dialog-close" aria-label="关闭">
              ×
            </Dialog.CloseButton>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog>
    )
  }

  return { ask, ConfirmDialog }
}

export default createConfirm
