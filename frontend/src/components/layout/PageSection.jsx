/**
 * PageSection：内容区块卡（旧版 components/layout/PageSection.vue 的同款行为）。
 * variant: 'default' | 'hero' | 'compact'。
 */
export default function PageSection(props) {
  return (
    <section class={`ff-panel ff-page-section ${props.variant ? `is-${props.variant}` : ''}`}>
      {(props.title || props.subtitle || props.actions) && (
        <div class="ff-section-head">
          <div>
            {props.title && <div class="ff-section-title">{props.title}</div>}
            {props.subtitle && <div class="ff-section-subtitle">{props.subtitle}</div>}
          </div>
          {props.actions && <div class="ff-section-actions">{props.actions}</div>}
        </div>
      )}
      <div>{props.children}</div>
    </section>
  )
}
