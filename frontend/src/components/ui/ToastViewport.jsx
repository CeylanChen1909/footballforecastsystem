import { For } from 'solid-js'
import { toasts, dismissToast } from '../../utils/toast'

export default function ToastViewport() {
  return (
    <div class="ff-toast-viewport" aria-live="polite">
      <For each={toasts()}>
        {(item) => (
          <div class={`ff-toast is-${item.type}`} role={item.type === 'error' ? 'alert' : 'status'}>
            <span>{item.message}</span>
            <button type="button" aria-label="关闭提示" onClick={() => dismissToast(item.id)}>
              ×
            </button>
          </div>
        )}
      </For>
    </div>
  )
}
