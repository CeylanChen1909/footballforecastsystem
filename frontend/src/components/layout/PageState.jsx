/**
 * PageState：空 / 错 / 加载态（旧版 components/layout/PageState.vue 的同款行为）。
 * type: 'empty' | 'error' | 'loading'；actionText 非空且非 loading 时显示操作按钮。
 */
export default function PageState(props) {
  const type = () => props.type || 'empty'
  const mark = () => (type() === 'loading' ? '' : type() === 'error' ? '!' : '—')
  return (
    <div
      class={`ff-state-block is-${type()}`}
      role={type() === 'error' ? 'alert' : type() === 'loading' ? 'status' : undefined}
      aria-live={type() === 'error' || type() === 'loading' ? 'polite' : undefined}
    >
      <span class="ff-state-mark" aria-hidden="true">
        {mark()}
      </span>
      <div>
        <p class="ff-state-title">{props.title}</p>
        {props.description && <p class="ff-state-desc">{props.description}</p>}
        {props.actionText && type() !== 'loading' && (
          <button type="button" class={`ff-btn ${type() === 'empty' ? 'ff-btn-plain' : 'ff-btn-primary'}`} onClick={() => props.onAction?.()}>
            {props.actionText}
          </button>
        )}
      </div>
    </div>
  )
}
