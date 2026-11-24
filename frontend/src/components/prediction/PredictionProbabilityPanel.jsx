/**
 * PredictionProbabilityPanel（自 Vue src/components/prediction/PredictionProbabilityPanel.vue 移植）。
 * Element Plus 替换：el-progress → div 进度条（轨道 + 填充，颜色取 props 传入的
 * home/draw/away 色值）；其余逻辑（领先卡判定、并列文案、警示文案）一字未动。
 */
import { createMemo } from 'solid-js'

export default function PredictionProbabilityPanel(props) {
  const items = createMemo(() => [
    { key: 'home', label: `${props.homeName || '主队'} 胜`, tag: '主队', tagClass: 'home-tag', color: props.homeColor || '#0f6b4d' },
    { key: 'draw', label: '平局', color: props.drawColor || '#909399' },
    { key: 'away', label: `${props.awayName || '客队'} 胜`, tag: '客队', tagClass: 'away-tag', color: props.awayColor || '#b27a18' },
  ].map(item => {
    const value = Number(props.probabilities?.[item.key] || 0)
    return { ...item, percent: Math.round(value * 100), text: `${(value * 100).toFixed(1)}%` }
  }).map((item, _, all) => ({ ...item, isLeading: item.percent > 0 && item.percent === Math.max(...all.map(entry => entry.percent)) })))

  const leadingSummary = createMemo(() => {
    const leading = items().filter(item => item.isLeading)
    if (!leading.length) return ''
    if (leading.length > 1) {
      return `当前最高概率并列：${leading.map(item => `${item.label} ${item.text}`).join(' / ')}`
    }
    const top = leading[0]
    return props.warning
      ? `当前倾向：${top.label}（${top.text}），数据或模型质量不足，仅供参考`
      : `当前倾向：${top.label}（${top.text}）`
  })

  return (
    <section class="prob-section" aria-label="预测概率分布">
      <div class="prob-header">
        <span>预测概率分布</span>
        <span class="prob-hint">{props.hint || (props.warning ? '特征或模型质量不足，仅供参考' : '来自正式预测服务')}</span>
      </div>
      {leadingSummary() && <p class={`leading-summary ${props.warning ? 'is-caution' : ''}`}>{leadingSummary()}</p>}
      <div class="prob-bars">
        {items().map(item => (
          <div class={`prob-item ${item.isLeading ? 'is-leading' : ''}`} aria-label={`${item.label} ${item.text}`}>
            <div class="prob-label">
              <span class="label-text">{item.label}</span>
              {item.tag && <span class={`team-tag ${item.tagClass}`}>{item.tag}</span>}
              {item.isLeading && <span class="leading-tag">最高概率</span>}
            </div>
            <div class="prob-bar-wrapper">
              <div class="prob-bar" role="progressbar" aria-valuenow={item.percent} aria-valuemin="0" aria-valuemax="100">
                <div class="prob-bar-fill" style={{ width: `${item.percent}%`, background: item.color }} />
              </div>
            </div>
            <div class="prob-value">{item.text}</div>
          </div>
        ))}
      </div>
    </section>
  )
}
