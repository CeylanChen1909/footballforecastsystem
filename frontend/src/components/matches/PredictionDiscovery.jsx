/**
 * PredictionDiscovery（自 Vue src/components/matches/PredictionDiscovery.vue 移植）。
 * 阶段 B 恢复渲染（Vue 原版只 import 后 void 掉）。props：items / teamNameMode / limit（默认 4）；
 * 回调 onPredict。空列表时不渲染（原版 v-if 同款）。
 * 注意：2026 样板层把模板里本就存在的 .discovery-league 节点恢复可见（原 display:none）。
 */
import { createMemo, For, Show } from 'solid-js'
import { isFinished as isFinishedMatch } from '../../utils/match'
import { getTeamDisplayName } from '../../utils/teamNames'

export default function PredictionDiscovery(props) {
  const visibleItems = createMemo(() => (Array.isArray(props.items) ? props.items : []).slice(0, props.limit ?? 4))
  const team = (match, side) => match?.teams?.[side] || {}
  const homeName = match => getTeamDisplayName(team(match, 'home').name || match?.homeTeamName || '主队', props.teamNameMode || 'zh')
  const awayName = match => getTeamDisplayName(team(match, 'away').name || match?.awayTeamName || '客队', props.teamNameMode || 'zh')
  const leagueName = match => match?.league?.name || match?.leagueName || '赛事'
  const itemKey = (match, index) => match?.matchId || match?.id || match?.fixtureId || `discover-${index}`
  const isFinished = match => isFinishedMatch(match)

  return (
    <Show when={visibleItems().length}>
      <section id="prediction-discovery" class="prediction-discovery" aria-labelledby="prediction-discovery-title">
        <div class="discovery-head">
          <div class="discovery-copy">
            <span class="discovery-kicker">焦点</span>
            <h2 id="prediction-discovery-title">精选预测入口</h2>
            <p>点开看本场概率与数据覆盖。</p>
          </div>
          <span class="discovery-more">来自比赛焦点</span>
        </div>
        <div class="discovery-list" role="list">
          <For each={visibleItems()}>
            {(match, index) => (
              <button
                type="button"
                class="discovery-chip"
                role="listitem"
                aria-label={`查看 ${homeName(match)} 对 ${awayName(match)} 的预测`}
                onClick={() => props.onPredict?.(match)}
              >
                <span class="discovery-league">{leagueName(match)}</span>
                <strong>{homeName(match)} <em>vs</em> {awayName(match)}</strong>
                <span class="discovery-cta">{isFinished(match) ? '复盘' : '预测'}</span>
              </button>
            )}
          </For>
        </div>
      </section>
    </Show>
  )
}
