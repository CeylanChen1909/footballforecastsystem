/**
 * MatchFocusRail（自 Vue src/components/matches/MatchFocusRail.vue 移植）。
 *
 * 阶段 B 恢复渲染：Vue 原版只 import 后 void 掉（LCP 临时阉割），本页按主 neon
 * 决定恢复挂载（见 sample-pages.md 报告 §阶段 B）。props / emits 与原版一致：
 * items / meta / loading / error（String）/ stale / teamNameMode；
 * 回调 onOpen / onPredict / onRetry / onViewAll。
 * 队徽加载失败时隐藏 img（Vue 的 onLogoError 同款：display none）。
 */
import { createMemo, For, Show } from 'solid-js'
import { formatMatchTime, getMatchTimestamp, getStatusText, isFinished as isFinishedMatch, isLive as isLiveMatch } from '../../utils/match'
import { getTeamDisplayName } from '../../utils/teamNames'
import { getMediaAssetUrl } from '../../utils/mediaAsset'

export default function MatchFocusRail(props) {
  const items = () => props.items || []
  const emptyTitle = createMemo(() => props.meta?.emptyReason === 'NO_MATCHES_IN_WINDOW' ? '当前日期没有比赛' : '暂无重点比赛')
  const emptyDescription = createMemo(() => props.meta?.emptyReason === 'NO_VISIBLE_FOCUS_MATCHES' ? '当前数据已读取，但没有符合焦点规则的赛事。' : '当前没有正在进行或临近开赛的重点赛事。')

  const team = (match, side) => match?.teams?.[side] || {}
  const homeName = match => getTeamDisplayName(team(match, 'home').name || match?.homeTeamName || '主队', props.teamNameMode || 'zh')
  const awayName = match => getTeamDisplayName(team(match, 'away').name || match?.awayTeamName || '客队', props.teamNameMode || 'zh')
  const leagueName = match => match?.league?.name || match?.leagueName || '其他赛事'
  const homeLogo = match => getMediaAssetUrl(team(match, 'home').logo || match?.homeTeamLogo || '')
  const awayLogo = match => getMediaAssetUrl(team(match, 'away').logo || match?.awayTeamLogo || '')
  const initial = name => String(name || '?').trim().slice(0, 1).toUpperCase()
  const recommendationKey = (match, index) => match?.matchId || match?.id || match?.fixtureId || `${homeName(match)}-${awayName(match)}-${index}`
  const tier = match => String(match?.recommendation?.tier || (isLiveMatch(match) ? 'LIVE' : isFinishedMatch(match) ? 'RECENT' : 'UPCOMING')).toUpperCase()
  const statusText = match => getStatusText(match) || (tier(match) === 'LIVE' ? '进行中' : tier(match) === 'RECENT' ? '已结束' : '未开始')
  const reasons = match => {
    const texts = Array.isArray(match?.recommendation?.reasonTexts) ? match.recommendation.reasonTexts : []
    const codes = Array.isArray(match?.recommendation?.reasonCodes) ? match.recommendation.reasonCodes : []
    if (!texts.length) return ['当前赛程焦点']
    const priority = { POPULAR_TEAM: 0, LIVE_NOW: 0, KICKOFF_SOON: 1, TOP_LEAGUE: 2, KEY_ROUND: 3, KICKOFF_TODAY: 4 }
    return texts.map((text, index) => ({ text, code: codes[index] || '' }))
      .sort((a, b) => (priority[a.code] ?? 9) - (priority[b.code] ?? 9))
      .map(item => item.text)
  }
  const timestamp = match => getMatchTimestamp(match)
  const kickoffLabel = match => timestamp(match) ? formatMatchTime(match) : '时间待同步'
  const scoreOrTime = match => {
    const home = match?.goals?.home ?? match?.homeScore
    const away = match?.goals?.away ?? match?.awayScore
    if ((tier(match) === 'LIVE' || tier(match) === 'RECENT') && home != null && away != null) return `${home} - ${away}`
    if (!timestamp(match)) return '—'
    return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit' }).format(new Date(timestamp(match)))
  }
  const onLogoError = event => { event.target.style.display = 'none' }

  return (
    <section class="focus-rail" aria-labelledby="match-focus-title">
      <div class="focus-head">
        <div>
          <span class="focus-kicker">赛程</span>
          <h2 id="match-focus-title">比赛焦点</h2>
        </div>
        <div class="focus-head-actions">
          <Show when={props.meta?.returnedCount != null}>
            <span class="focus-count">{props.meta.returnedCount} 场</span>
          </Show>
          <Show when={props.error && items().length}>
            <button type="button" class="focus-retry-link" onClick={() => props.onRetry?.()}>重新更新</button>
          </Show>
        </div>
      </div>

      <Show
        when={!props.loading}
        fallback={
          <div class="focus-list focus-list-skeleton" aria-live="polite" aria-label="正在加载比赛焦点">
            <For each={[1, 2, 3]}>{(index) => <div class="focus-skeleton-card"><i /><b /><em /></div>}</For>
          </div>
        }
      >
        <Show
          when={!(props.error && !items().length)}
          fallback={
            <div class="focus-state focus-state-error" role="alert">
              <strong>比赛焦点暂时无法更新</strong>
              <span>{props.error}</span>
              <button type="button" onClick={() => props.onRetry?.()}>重试</button>
            </div>
          }
        >
          <Show
            when={items().length}
            fallback={
              <div class="focus-state" aria-live="polite">
                <strong>{emptyTitle()}</strong>
                <span>{emptyDescription()}</span>
                <button type="button" onClick={() => props.onViewAll?.()}>查看全部赛程</button>
              </div>
            }
          >
            <div class={`focus-list ${props.stale ? 'is-stale' : ''}`}>
              <For each={items()}>
                {(match, index) => (
                  <article class={`focus-card is-${tier(match)}`}>
                    <button type="button" class="focus-main" onClick={() => props.onOpen?.(match)}>
                      <span class="focus-card-top">
                        <span class="focus-rank">{String(index() + 1).padStart(2, '0')}</span>
                        <span class="focus-league">{leagueName(match)}</span>
                        <span class="focus-status"><Show when={tier(match) === 'LIVE'}><i /></Show>{statusText(match)}</span>
                      </span>
                      <span class="focus-matchup">
                        <span class="focus-team">
                          <Show when={homeLogo(match)} fallback={<b title="暂无队徽" aria-label={`${homeName(match)}暂无队徽`}>{initial(homeName(match))}</b>}>
                            <img src={homeLogo(match)} width="24" height="24" loading="lazy" decoding="async" alt={`${homeName(match)}队徽`} onError={onLogoError} />
                          </Show>
                          <strong>{homeName(match)}</strong>
                        </span>
                        <span class="focus-center"><b>{scoreOrTime(match)}</b><small>{tier(match) === 'LIVE' ? '进行中' : kickoffLabel(match)}</small></span>
                        <span class="focus-team away">
                          <strong>{awayName(match)}</strong>
                          <Show when={awayLogo(match)} fallback={<b title="暂无队徽" aria-label={`${awayName(match)}暂无队徽`}>{initial(awayName(match))}</b>}>
                            <img src={awayLogo(match)} width="24" height="24" loading="lazy" decoding="async" alt={`${awayName(match)}队徽`} onError={onLogoError} />
                          </Show>
                        </span>
                      </span>
                      <span class="focus-reasons">
                        <For each={reasons(match).slice(0, 2)}>{(reason) => <em>{reason}</em>}</For>
                      </span>
                    </button>
                    <div class="focus-card-actions">
                      <button type="button" onClick={() => props.onPredict?.(match)}>{tier(match) === 'RECENT' ? '查看复盘' : '查看预测'}</button>
                    </div>
                  </article>
                )}
              </For>
            </div>
          </Show>
        </Show>
      </Show>
      <Show when={props.stale}>
        <p class="focus-stale-note" role="status">推荐数据暂未刷新，显示上次成功读取的结果。</p>
      </Show>
    </section>
  )
}
