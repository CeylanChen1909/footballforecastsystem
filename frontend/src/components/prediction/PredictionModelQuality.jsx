/**
 * PredictionModelQuality（自 Vue src/components/prediction/PredictionModelQuality.vue 移植）。
 * el-tag → span.ff-tag；其余逻辑一字未动（指标按 model_* 前缀取数、门槛提示、
 * 覆盖范围文案）。visible 为 false 时不渲染（原版 v-if 同款）。
 */
import { createMemo, Show } from 'solid-js'

export default function PredictionModelQuality(props) {
  const quality = () => props.quality || {}
  const value = key => {
    const number = Number(quality()?.[key])
    return Number.isFinite(number) ? number : null
  }
  const percent = v => `${(Number(v) * 100).toFixed(1)}%`
  const number = v => Number(v).toFixed(3)
  const scopeText = createMemo(() => {
    const competitions = quality()?.model_scope?.competitions
    return Array.isArray(competitions) && competitions.length ? `当前模型覆盖：${competitions.join('、')}` : ''
  })

  return (
    <Show when={props.visible}>
      <div class="model-quality-panel" aria-label="模型质量说明">
        <div class="quality-panel-head">
          <div><strong>模型可靠性</strong><small>基于时间外测试集，不代表单场结果保证</small></div>
          <span class={`ff-tag ${props.reliable ? 'is-success' : 'is-warning'}`}>{props.reliable ? '测试表现较稳健' : '建议谨慎参考'}</span>
        </div>
        <div class="quality-metric-grid">
          <Show when={value('model_accuracy') != null}><div><span>时间外准确率</span><strong>{percent(value('model_accuracy'))}</strong></div></Show>
          <Show when={value('model_balanced_accuracy') != null}><div><span>综合类别准确率</span><strong>{percent(value('model_balanced_accuracy'))}</strong></div></Show>
          <Show when={value('model_log_loss') != null}><div><span>概率误差 Log loss</span><strong>{number(value('model_log_loss'))}</strong></div></Show>
          <Show when={value('model_baseline_log_loss') != null}><div><span>基线概率误差</span><strong>{number(value('model_baseline_log_loss'))}</strong></div></Show>
          <Show when={value('model_test_size') != null}><div><span>时间外测试场次</span><strong>{value('model_test_size')}</strong></div></Show>
          <Show when={quality().model_class_recall?.DRAW != null}><div><span>平局识别率</span><strong>{percent(quality().model_class_recall.DRAW)}</strong></div></Show>
        </div>
        <Show when={quality().model_quality_gate}>
          <div class={`quality-gate-note ${quality().model_quality_gate.eligible ? 'gate-pass' : 'gate-hold'}`}>
            <strong>{quality().model_quality_gate.eligible ? '已通过生产门槛' : '当前模型未通过生产门槛'}</strong>
            <span>{quality().model_quality_gate.message || '模型结果仅作为实验性参考'}</span>
          </div>
        </Show>
        <Show when={scopeText()}><small class="quality-scope-warning">{scopeText()}</small></Show>
        <Show when={quality().model_scope?.warning}><small class="quality-scope-warning">{quality().model_scope.warning}</small></Show>
      </div>
    </Show>
  )
}
