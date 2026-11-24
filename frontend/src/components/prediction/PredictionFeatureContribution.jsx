/**
 * PredictionFeatureContribution（自 Vue src/components/prediction/PredictionFeatureContribution.vue 移植）。
 * el-icon → 内联 SVG；items 为空时不渲染（原版 v-if 同款）。
 */
import { Show } from 'solid-js'
import { TrendIcon } from '../ui/icons'

export default function PredictionFeatureContribution(props) {
  return (
    <Show when={props.items?.length}>
      <div class="feature-contrib">
        <div class="contrib-header"><TrendIcon size={16} /><span>参考指标</span><small>展示影响较大的输入，不等同于因果解释</small></div>
        <div class="contrib-list">
          {props.items.map(item => (
            <div class="contrib-item">
              <div class="contrib-topline">
                <div class="contrib-name-wrap"><span class="contrib-name">{item.label}</span><span class="contrib-feature">模型参考输入</span></div>
                <span class="contrib-value">{item.valueText}</span>
              </div>
              <div class="contrib-detail">{item.detail}</div>
              <div class="contrib-bar"><div class="contrib-fill" style={{ width: `${item.percent}%` }} /></div>
            </div>
          ))}
        </div>
      </div>
    </Show>
  )
}
