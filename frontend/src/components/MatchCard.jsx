/**
 * MatchCard（自 Vue src/components/MatchCard.vue 移植）。
 * Element Plus 替换：el-button → button.ff-btn；el-tag → span.ff-tag；
 * el-dropdown → 手写 popover 菜单；el-tooltip → title 属性；el-icon → 内联 SVG。
 * 逻辑与文案一字未动：队徽 wikimedia 改写 + getMediaAssetUrl、比分/时间
 * 判定、焦点行、主操作文案（查看预测/查看复盘，移动端短文案 预测/复盘）。
 */
import { createEffect, createMemo, createSignal, Show } from 'solid-js'
import { formatMatchTime, getAwayTeam, getDisplayStatusKey, getHomeTeam, getMatchId, getMatchTimestamp, getStatusText, isFinished as isFinishedMatch, isLive as isLiveMatch } from '../utils/match'
import { getTeamDisplayName } from '../utils/teamNames'
import { getMediaAssetUrl } from '../utils/mediaAsset'
import { ChatIcon, DataIcon, LocationIcon, MoreIcon, StarFilledIcon, StarIcon, TrophyIcon } from './ui/icons'

export default function MatchCard(props) {
  const match = () => props.match || {}
  const [homeLogoBroken, setHomeLogoBroken] = createSignal(false)
  const [awayLogoBroken, setAwayLogoBroken] = createSignal(false)
  const [menuOpen, setMenuOpen] = createSignal(false)

  const fixture = createMemo(() => ({
    ...(match()?.fixture || {}),
    date: match()?.fixture?.date || match()?.matchTime,
    id: match()?.fixture?.id || getMatchId(match()),
    status: { ...(match()?.fixture?.status || {}), short: match()?.fixture?.status?.short || match()?.status },
  }))
  const homeTeam = createMemo(() => {
    const team = getHomeTeam(match()) || {}
    return { ...team, name: team.name || match()?.homeTeamName || '主队', logo: team.logo || match()?.homeTeamLogo || '' }
  })
  const awayTeam = createMemo(() => {
    const team = getAwayTeam(match()) || {}
    return { ...team, name: team.name || match()?.awayTeamName || '客队', logo: team.logo || match()?.awayTeamLogo || '' }
  })
  const homeDisplayName = createMemo(() => getTeamDisplayName(homeTeam().name, props.teamNameMode || 'zh'))
  const awayDisplayName = createMemo(() => getTeamDisplayName(awayTeam().name, props.teamNameMode || 'zh'))
  const goals = createMemo(() => match()?.goals || { home: match()?.homeScore, away: match()?.awayScore })

  const normalizeLogoUrl = (url) => {
    if (!url) return ''
    const value = String(url).trim()
    if (!value) return ''
    if (value.includes('commons.wikimedia.org/wiki/Special:FilePath/')) return value
    const wikiMatch = value.match(/\/([^/]+)$/)
    if (value.includes('upload.wikimedia.org') && wikiMatch?.[1]) return `https://commons.wikimedia.org/wiki/Special:FilePath/${wikiMatch[1]}`
    return value
  }
  const homeLogoSrc = createMemo(() => getMediaAssetUrl(normalizeLogoUrl(homeTeam()?.logo)))
  const awayLogoSrc = createMemo(() => getMediaAssetUrl(normalizeLogoUrl(awayTeam()?.logo)))
  const homeLogoVisible = createMemo(() => !!homeLogoSrc() && !homeLogoBroken())
  const awayLogoVisible = createMemo(() => !!awayLogoSrc() && !awayLogoBroken())
  // Vue watch(homeLogoSrc): src 变化时复位 broken 标记。
  createEffect(() => homeLogoSrc(), () => { setHomeLogoBroken(false) })
  createEffect(() => awayLogoSrc(), () => { setAwayLogoBroken(false) })

  const isLive = createMemo(() => isLiveMatch(match()))
  const isFinished = createMemo(() => isFinishedMatch(match()))
  const matchTimestamp = createMemo(() => getMatchTimestamp(match()))
  const statusLabel = createMemo(() => getStatusText(match()))
  const statusType = createMemo(() => isLive() ? 'is-danger' : isFinished() ? 'is-success' : getDisplayStatusKey(match()) === 'STALE' ? 'is-warning' : 'is-info')
  const primaryActionLabel = createMemo(() => isFinished() ? '查看复盘' : '查看预测')

  const formatTime = (m) => {
    const full = formatMatchTime(m)
    if (full.includes('时间待同步')) return '时间待同步'
    const timestamp = getMatchTimestamp(m)
    return timestamp ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit' }).format(new Date(timestamp)) : '时间待同步'
  }

  const handleMoreCommand = (command) => {
    setMenuOpen(false)
    if (command === 'h2h') {
      props.onH2h?.(fixture().id, homeTeam()?.id, awayTeam()?.id, homeTeam()?.name, awayTeam()?.name)
    } else if (command === 'agent') {
      props.onAgent?.(match())
    } else if (command === 'details') {
      props.onDetails?.(match())
    }
  }

  // 下拉菜单打开时，点外部关闭。
  createEffect(
    () => menuOpen(),
    (open) => {
      if (!open) return
      const onDocClick = (e) => {
        if (!e.target.closest?.('.more-actions')) setMenuOpen(false)
      }
      const onKey = (e) => { if (e.key === 'Escape') setMenuOpen(false) }
      document.addEventListener('click', onDocClick)
      document.addEventListener('keydown', onKey)
      return () => {
        document.removeEventListener('click', onDocClick)
        document.removeEventListener('keydown', onKey)
      }
    },
  )

  const teamKey = (team, side, e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      e.stopPropagation()
      props.onTeamClick?.(team?.name, side, match())
    }
  }

  return (
    <div class={`match-card ${props.focused ? 'is-focus' : ''}`} data-match-id={getMatchId(match())}>
      <div class="match-info">
        <div class="team-row">
          <div class="team">
            <Show
              when={homeLogoVisible()}
              fallback={
                <span class="logo-placeholder" title="暂无队徽" aria-label={`${homeDisplayName()}暂无队徽，查看资料`} role="button" tabindex="0" onClick={(e) => { e.stopPropagation(); props.onTeamClick?.(homeTeam()?.name, 'home', match()) }} onKeyDown={(e) => teamKey(homeTeam(), 'home', e)}>
                  {homeDisplayName()?.[0]}
                </span>
              }
            >
              <img src={homeLogoSrc()} class="team-logo" width="30" height="30" loading="lazy" decoding="async" alt={`${homeDisplayName()}队徽`} onError={() => setHomeLogoBroken(true)} onClick={(e) => { e.stopPropagation(); props.onTeamClick?.(homeTeam()?.name, 'home', match()) }} />
            </Show>
            <span class="team-name" role="button" tabindex="0" title={homeDisplayName()} aria-label={`查看${homeDisplayName()}资料`} onClick={(e) => { e.stopPropagation(); props.onTeamClick?.(homeTeam()?.name, 'home', match()) }} onKeyDown={(e) => teamKey(homeTeam(), 'home', e)}>
              {homeDisplayName()}
            </span>
          </div>
          <div class="score">
            <Show
              when={(isFinished() || isLive()) && goals()?.home != null && goals()?.away != null}
              fallback={<span class={`match-time ${!matchTimestamp() ? 'is-unknown' : ''}`}>{isFinished() ? '比分待同步' : matchTimestamp() ? formatTime(match()) : '时间待同步'}</span>}
            >
              <span class="score-text">{goals().home} - {goals().away}</span>
            </Show>
          </div>
          <div class="team">
            <span class="team-name" role="button" tabindex="0" title={awayDisplayName()} aria-label={`查看${awayDisplayName()}资料`} onClick={(e) => { e.stopPropagation(); props.onTeamClick?.(awayTeam()?.name, 'away', match()) }} onKeyDown={(e) => teamKey(awayTeam(), 'away', e)}>
              {awayDisplayName()}
            </span>
            <Show
              when={awayLogoVisible()}
              fallback={
                <span class="logo-placeholder" title="暂无队徽" aria-label={`${awayDisplayName()}暂无队徽，查看资料`} role="button" tabindex="0" onClick={(e) => { e.stopPropagation(); props.onTeamClick?.(awayTeam()?.name, 'away', match()) }} onKeyDown={(e) => teamKey(awayTeam(), 'away', e)}>
                  {awayDisplayName()?.[0]}
                </span>
              }
            >
              <img src={awayLogoSrc()} class="team-logo" width="30" height="30" loading="lazy" decoding="async" alt={`${awayDisplayName()}队徽`} onError={() => setAwayLogoBroken(true)} onClick={(e) => { e.stopPropagation(); props.onTeamClick?.(awayTeam()?.name, 'away', match()) }} />
            </Show>
          </div>
        </div>
        <div class="match-meta">
          <Show when={fixture()?.venue?.name}>
            <div class="venue"><LocationIcon size={12} />{fixture().venue.name}</div>
          </Show>
          <Show when={match()?.league?.round}>
            <div class="round"><TrophyIcon size={12} />{match().league.round}</div>
          </Show>
          <div class="status">
            <Show when={props.focused}><span class="focus-mark">焦点</span></Show>
            <span class={`ff-tag ${statusType()}`}>
              <Show when={isLive()}><span class="live-dot" /></Show>
              {statusLabel()}
            </span>
          </div>
        </div>
      </div>
      <div class="action-area" aria-label="比赛操作">
        <button type="button" class="ff-btn ff-btn-primary ff-btn-sm action-primary" aria-label={`${primaryActionLabel()}：打开本场分析`} onClick={(e) => { e.stopPropagation(); props.onPredict?.(match()) }}>
          <span class="action-copy-full">{primaryActionLabel()}</span>
          <span class="action-copy-short">{isFinished() ? '复盘' : '预测'}</span>
        </button>
        <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm action-details" onClick={(e) => { e.stopPropagation(); props.onDetails?.(match()) }}>
          赛事数据
        </button>
        <button
          type="button"
          class={`ff-btn ff-btn-ghost ff-btn-sm favorite-btn ${props.favorited ? 'is-favorited' : ''}`}
          title={props.favorited ? '取消收藏' : '收藏比赛'}
          aria-label={props.favorited ? '取消收藏比赛' : '收藏比赛'}
          onClick={(e) => { e.stopPropagation(); props.onFavoriteMatch?.(match()) }}
        >
          {props.favorited ? <StarFilledIcon size={14} /> : <StarIcon size={14} />}
        </button>
        <div class="more-actions">
          <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm more-btn" aria-label="更多比赛操作" title="更多比赛操作" onClick={(e) => { e.stopPropagation(); setMenuOpen(!menuOpen()) }}>
            <MoreIcon size={14} />
          </button>
          <Show when={menuOpen()}>
            <div class="more-menu" role="menu">
              <button type="button" role="menuitem" onClick={() => handleMoreCommand('h2h')}><DataIcon size={14} />历史交锋</button>
              <button type="button" role="menuitem" onClick={() => handleMoreCommand('agent')}><ChatIcon size={14} />AI 助手</button>
              <button type="button" role="menuitem" class="is-divided" onClick={() => handleMoreCommand('details')}>赛事数据</button>
            </div>
          </Show>
        </div>
      </div>
    </div>
  )
}
