/**
 * CompetitionHub 页（自 Vue src/views/user/CompetitionHub.vue 506 行移植）。
 *
 * 对齐要点（契约 §1.4/§2.3）：
 *  - 积分榜 + 球队资料；getStandingSeasons / getStandingsByLeagueName /
 *    getTeamsByLeague 用 Promise.allSettled，两路都失败才抛错；quality 与
 *    zoneRules 只在 preserveMeta 时才存在（见 api/index.js）。
 *  - el-table → 手写 <table>：去 stripe、去外框线与竖线，字号 13px；
 *    移动端卡片切换保留（.standings-table-desktop 在 768px 下隐藏改卡片渲染，
 *    见 competitions.css 末段媒体查询，与原版一致）。
 *  - el-select / el-input → 原生 select / input（.ff-select / .ff-input）。
 *  - 顶栏/页脚由 App.jsx 全局挂载，本页只写页面内容，不嵌 AppTopNav。
 *  - 路由 query：league / q（AppTopNav 搜索跳联赛用 league）。
 *  - Solid 2.0 注意：effect 回调里不直读 searchParams（STRICT_READ_UNTRACKED），
 *    query 值先在 compute 里取出；setSelectedLeague 后立即读信号会读到旧值，
 *    故 loadLeagueData 用本地值传参，不读刚 set 的信号。
 */
import { createEffect, createMemo, createSignal, For, Show } from 'solid-js'
import { useNavigate, useSearchParams } from '@solidjs/router'
import { analyticsApi, crawlerApi } from '../../api'
import auth from '../../stores/auth'
import { toastSuccess, toastWarning } from '../../utils/toast'
import { getMediaAssetUrl } from '../../utils/mediaAsset'
import PageState from '../../components/layout/PageState'
import '../../styles/competitions.css'
import '../../styles/page-common.css'

const leagueOptions = [
  { value: '英超', label: '英超', id: 39 },
  { value: '西甲', label: '西甲', id: 140 },
  { value: '意甲', label: '意甲', id: 135 },
  { value: '德甲', label: '德甲', id: 78 },
  { value: '法甲', label: '法甲', id: 61 },
  { value: '荷甲', label: '荷甲', id: 88 },
  { value: '葡超', label: '葡超', id: 94 },
  { value: '英冠', label: '英冠', id: 40 },
]

const normalizeList = (value) => {
  if (Array.isArray(value)) return value
  if (Array.isArray(value?.response)) return value.response
  if (Array.isArray(value?.items)) return value.items
  if (Array.isArray(value?.data?.response)) return value.data.response
  return []
}
const normalizeStanding = (row) => ({
  ...row,
  rank: Number(row?.rank || row?.position || 0),
  team: row?.team || { name: row?.teamName, logo: row?.teamLogo, id: row?.teamId },
  win: Number(row?.win ?? row?.wins ?? 0),
  draw: Number(row?.draw ?? row?.draws ?? 0),
  loss: Number(row?.loss ?? row?.losses ?? 0),
})
const normalizeClub = (club) => {
  const team = club?.team || club
  return {
    id: team?.id || club?.id || '',
    canonicalKey: team?.canonicalKey || club?.canonicalKey || '',
    name: team?.name || club?.name || '',
    logo: team?.logo || club?.logo || '',
    logoBroken: false,
  }
}
const zoneLabelMap = {
  CHAMPIONS_LEAGUE: '欧冠正赛',
  CHAMPIONS_LEAGUE_QUALIFYING: '欧冠资格赛',
  EUROPA_LEAGUE: '欧联正赛',
  EUROPA_LEAGUE_QUALIFYING: '欧联资格赛',
  CONFERENCE_LEAGUE: '欧协联正赛',
  CONFERENCE_LEAGUE_QUALIFYING: '欧协联资格赛',
  CONFERENCE_PLAYOFF: '欧协联附加赛',
  PROMOTION: '直接升级',
  PROMOTION_PLAYOFF: '升级附加赛',
  RELEGATION_PLAYOFF: '降级附加赛',
  RELEGATION: '降级',
  EUROPA: '欧战区',
}

export default function CompetitionHub() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [selectedLeague, setSelectedLeague] = createSignal('英超')
  const [selectedSeason, setSelectedSeason] = createSignal('')
  const [seasonOptions, setSeasonOptions] = createSignal([])
  const [loading, setLoading] = createSignal(false)
  const [loadError, setLoadError] = createSignal('')
  const [standings, setStandings] = createSignal([])
  const [clubsFromApi, setClubsFromApi] = createSignal([])
  const [clubKeyword, setClubKeyword] = createSignal('')
  const [zoneRules, setZoneRules] = createSignal({ zones: [], note: '' })
  const [quality, setQuality] = createSignal({ status: 'UNKNOWN', statusText: '', message: '', source: '', lastSyncedAt: '', ageMinutes: -1 })
  // 破损队徽集合（signal 存 Set；直接改 row.logo 不会触发重渲染，故另记）。
  const [brokenLogos, setBrokenLogos] = createSignal(new Set())
  const isAdmin = createMemo(() => ['ADMIN', 'SUPER_ADMIN'].includes(auth.role()))

  const clubs = createMemo(() => {
    const map = new Map()
    standings().forEach((row) => {
      const club = normalizeClub(row.team)
      if (club.name) map.set(club.canonicalKey || club.name, { ...club, rank: row.rank, points: row.points, zone: row.zone || '' })
    })
    if (!standings().length) {
      clubsFromApi().forEach((item) => {
        const club = normalizeClub(item)
        if (club.name && !map.has(club.canonicalKey || club.name)) map.set(club.canonicalKey || club.name, club)
      })
    }
    return [...map.values()].sort((a, b) => (a.rank || 999) - (b.rank || 999) || a.name.localeCompare(b.name, 'zh-CN'))
  })
  const filteredClubs = createMemo(() => {
    const keyword = clubKeyword().trim().toLowerCase()
    return keyword ? clubs().filter((club) => club.name.toLowerCase().includes(keyword)) : clubs()
  })
  const standingDataState = createMemo(() => {
    if (!standings().length) return 'EMPTY'
    const hasPlayedData = standings().some(
      (row) => Number(row.played || 0) > 0 || Number(row.points || 0) > 0 || Number(row.win || 0) > 0 || Number(row.draw || 0) > 0 || Number(row.loss || 0) > 0,
    )
    if (hasPlayedData) return 'READY'
    const status = String(quality().status || '').toUpperCase()
    return ['AVAILABLE', 'NORMAL', 'PRESEASON', 'STALE'].includes(status) && quality().lastSyncedAt
      ? (status === 'STALE' ? 'STALE' : 'PRESEASON')
      : 'INCOMPLETE'
  })
  const qualityMessage = createMemo(() => quality().message || '')
  const qualityTagType = createMemo(
    () =>
      ({ PRESEASON: 'is-warning', STALE: 'is-warning', AVAILABLE: 'is-success', NORMAL: 'is-success' }[
        String(quality().status || '').toUpperCase()
      ] || 'is-info'),
  )

  const rankClass = (row) => {
    const zone = String(row?.zone || '').toUpperCase()
    if (zone.startsWith('CHAMPIONS_') || zone.startsWith('EUROPA_') || zone.startsWith('CONFERENCE_') || zone === 'PROMOTION' || zone === 'PROMOTION_PLAYOFF')
      return 'rank-top'
    if (zone.startsWith('RELEGATION')) return 'rank-bottom'
    return ''
  }
  const zoneLabel = (zone) => zoneLabelMap[zone] || ''
  const firstLetter = (name) => String(name || '?').trim().slice(0, 1).toUpperCase()
  const clubRank = (club) => club.rank || standings().find((row) => row.team?.name === club.name)?.rank || 0
  const markLogoBroken = (logo) => {
    if (!logo) return
    setBrokenLogos((prev) => new Set(prev).add(logo))
  }
  const logoOk = (logo) => Boolean(logo) && !brokenLogos().has(logo)

  const loadLeagueData = async (leagueArg) => {
    const league = leagueArg || selectedLeague()
    setLoading(true)
    setLoadError('')
    try {
      const seasonsResult = await crawlerApi.getStandingSeasons(league).catch(() => [])
      const seasons = normalizeList(seasonsResult).map(String).filter(Boolean)
      setSeasonOptions(seasons)
      const season = !selectedSeason() || !seasons.includes(selectedSeason()) ? seasons[0] || '' : selectedSeason()
      setSelectedSeason(season)
      const [standingsResult, clubsResult] = await Promise.allSettled([
        crawlerApi.getStandingsByLeagueName(league, season),
        crawlerApi.getTeamsByLeague(league),
      ])
      const nextStandings =
        standingsResult.status === 'fulfilled' ? normalizeList(standingsResult.value).map(normalizeStanding).filter((row) => row.team?.name) : []
      const nextClubs = clubsResult.status === 'fulfilled' ? normalizeList(clubsResult.value) : []
      if (!nextStandings.length && !nextClubs.length && standingsResult.status === 'rejected' && clubsResult.status === 'rejected') {
        throw standingsResult.reason || clubsResult.reason || new Error('资料服务暂时不可用')
      }
      setStandings(nextStandings)
      setClubsFromApi(nextClubs)
      setQuality(
        standingsResult.status === 'fulfilled'
          ? standingsResult.value?.dataQuality || standingsResult.value?.data?.dataQuality || quality()
          : { status: 'SYNC_FAILED', statusText: '同步失败', message: standingsResult.reason?.message || '积分榜请求失败', source: '' },
      )
      setZoneRules(
        standingsResult.status === 'fulfilled'
          ? standingsResult.value?.zoneRules || standingsResult.value?.data?.zoneRules || { zones: [], note: '' }
          : { zones: [], note: '' },
      )
      analyticsApi
        .track('competition_viewed', {
          page: '/competitions',
          entityType: 'league',
          entityId: league,
          properties: { season, standings: nextStandings.length, clubs: nextClubs.length, dataStatus: quality().status },
        })
        .catch(() => {})
    } catch (error) {
      // 本地值直传：set 后立即读信号在 2.0 暂缓写入下会读到旧值（样板页踩过的坑）。
      const message = error?.message || '积分榜暂时不可用，请稍后重试'
      setStandings([])
      setClubsFromApi([])
      setZoneRules({ zones: [], note: '' })
      setLoadError(message)
      setQuality({ status: 'SYNC_FAILED', statusText: '同步失败', message, source: '' })
    } finally {
      setLoading(false)
    }
  }

  const refreshStandings = async () => {
    if (!isAdmin()) return
    setLoading(true)
    try {
      await crawlerApi.refreshStandings(selectedLeague())
      toastSuccess('积分榜同步任务已完成')
      await loadLeagueData()
    } catch (error) {
      toastWarning(error?.message || '积分榜同步失败，请检查数据源状态')
    } finally {
      setLoading(false)
    }
  }

  const openTeam = (team) => {
    const next = normalizeClub(team)
    if (!next.name) return
    analyticsApi
      .track('competition_team_opened', {
        page: '/competitions',
        entityType: 'team',
        entityId: next.id || next.name,
        properties: { league: selectedLeague(), season: selectedSeason() },
      })
      .catch(() => {})
    const query = new URLSearchParams({
      name: next.name,
      teamId: String(next.id || ''),
      logo: next.logo || '',
      league: selectedLeague(),
      season: selectedSeason(),
    })
    navigate(`/team/${encodeURIComponent(String(next.id || next.name))}/squad?${query.toString()}`)
  }

  const goMatchesDiscover = () => navigate('/matches?discover=predict')

  // 路由 query 变化（含首次挂载）时应用联赛并拉数据：query 值在 compute 里取，
  // effect 回调只用 compute 传回的本地值，避免 Solid 2.0 的暂缓写入与
  // STRICT_READ_UNTRACKED 坑（见文件头注释）。
  createEffect(
    () => String(searchParams.league || searchParams.q || '').trim(),
    (requested) => {
      let league = selectedLeague()
      if (requested) {
        const hit = leagueOptions.find((item) => item.value === requested || item.label === requested)
        if (hit) {
          league = hit.value
          setSelectedLeague(league)
        }
      }
      loadLeagueData(league)
    },
  )

  return (
    <div class="competition-page">
      <div class="competition-main">
        <div class="hub-grid">
          <section class="ff-panel ff-page-section standings-panel">
            <div class="ff-section-head">
              <div>
                <div class="ff-section-title">联赛积分榜</div>
                <div class="ff-section-subtitle">按已同步赛季显示，点球队看资料</div>
              </div>
              <div class="ff-section-actions">
                <div class="standings-toolbar" aria-label="积分榜筛选与操作">
                  <div class="toolbar-group toolbar-primary">
                    <label class="control-field">
                      <span>联赛</span>
                      <select
                        class="ff-select league-select"
                        aria-label="选择联赛"
                        value={selectedLeague()}
                        onChange={(e) => {
                          setSelectedLeague(e.currentTarget.value)
                          loadLeagueData(e.currentTarget.value)
                        }}
                      >
                        <For each={leagueOptions}>{(league) => <option value={league.value}>{league.label}</option>}</For>
                      </select>
                    </label>
                    <label class="control-field">
                      <span>赛季</span>
                      <select
                        class="ff-select season-select"
                        aria-label="选择赛季"
                        disabled={!seasonOptions().length}
                        value={selectedSeason()}
                        onChange={(e) => {
                          setSelectedSeason(e.currentTarget.value)
                          loadLeagueData()
                        }}
                      >
                        <For each={seasonOptions()}>{(season) => <option value={season}>{season}</option>}</For>
                      </select>
                    </label>
                  </div>
                  <div class="toolbar-group toolbar-secondary">
                    <span class="ff-tag is-info">{loading() ? '…' : `${standings().length} 队`}</span>
                    <Show when={quality().statusText}>
                      <span class={`ff-tag ${qualityTagType()}`}>{quality().statusText}</span>
                    </Show>
                    <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" aria-label="重新读取积分榜" title="重新读取积分榜快照" disabled={loading()} onClick={() => loadLeagueData()}>
                      ↻
                    </button>
                    <Show when={isAdmin()}>
                      <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" disabled={loading()} onClick={refreshStandings}>
                        管理员同步
                      </button>
                    </Show>
                  </div>
                </div>
              </div>
            </div>
            <div>
              <Show when={loading()}>
                <div class="standings-skeleton" role="status" aria-live="polite" aria-busy="true" aria-label="正在加载积分榜">
                  <For each={[1, 2, 3, 4, 5, 6, 7, 8]}>
                    {() => (
                      <div class="standings-skeleton-row">
                        <i />
                        <b />
                        <em />
                        <em />
                        <em />
                      </div>
                    )}
                  </For>
                </div>
              </Show>
              <Show when={!loading() && loadError()}>
                <PageState type="error" title="积分榜暂时不可用" description={loadError() || '联赛积分快照读取失败，可稍后重试，或先从赛程页浏览比赛与预测。'} actionText="重试" onAction={() => loadLeagueData()} />
              </Show>
              <Show when={!loading() && !loadError() && standings().length && standingDataState() !== 'INCOMPLETE'}>
                <div class="standings-data-wrap">
                  <Show when={standingDataState() !== 'READY'}>
                    <div class="ff-alert is-info">
                      <strong>{standingDataState() === 'PRESEASON' ? '赛季尚未产生积分' : '积分数据不完整'}</strong>
                      <span>{qualityMessage() || '当前仅显示参赛名单或缓存快照，积分列不会被当作真实成绩。'}</span>
                    </div>
                  </Show>
                  <Show when={zoneRules().zones?.length}>
                    <div class="zone-legend" aria-label="积分榜区域说明">
                      <For each={zoneRules().zones}>
                        {(rule) => (
                          <span class="zone-legend-item">
                            <span class={`zone-label zone-${String(rule.code || '').toLowerCase()}`}>{rule.label}</span>
                            <span class="zone-range">{rule.from === rule.to ? `第 ${rule.from} 名` : `第 ${rule.from}–${rule.to} 名`}</span>
                          </span>
                        )}
                      </For>
                      <Show when={zoneRules().note}>
                        <span class="zone-note">{zoneRules().note}</span>
                      </Show>
                    </div>
                  </Show>
                  <p class="standings-scroll-hint" aria-hidden="true">
                    左右滑动查看完整积分榜
                  </p>
                  <div class="standings-table-scroll" role="region" aria-label="积分榜表格，可横向滚动">
                    <table class="standings-table standings-table-desktop">
                      <thead>
                        <tr>
                          <th style="width:48px">#</th>
                          <th style="width:112px">区域</th>
                          <th>球队</th>
                          <th style="width:52px">赛</th>
                          <th style="width:52px">胜</th>
                          <th style="width:52px">平</th>
                          <th style="width:52px">负</th>
                          <th style="width:64px">净胜</th>
                          <th style="width:118px">近况</th>
                          <th style="width:64px">积分</th>
                        </tr>
                      </thead>
                      <tbody>
                        <For each={standings()}>
                          {(row) => (
                            <tr>
                              <td>
                                <span class={`rank-number ${rankClass(row)}`}>{row.rank}</span>
                              </td>
                              <td>
                                <Show when={row.zone} fallback={<span class="zone-label is-empty">—</span>}>
                                  <span class={`zone-label zone-${String(row.zone).toLowerCase()}`}>{row.zoneLabel || zoneLabel(row.zone)}</span>
                                </Show>
                              </td>
                              <td>
                                <button type="button" class="team-cell" aria-label={`查看${row.team?.name || '未知球队'}资料`} onClick={() => openTeam(row.team)}>
                                  <Show
                                    when={logoOk(row.team?.logo)}
                                    fallback={
                                      <span class="mini-logo" title="暂无队徽" aria-label={`${row.team?.name || '球队'}暂无队徽`}>
                                        {firstLetter(row.team?.name)}
                                      </span>
                                    }
                                  >
                                    <img src={getMediaAssetUrl(row.team.logo)} alt="" aria-hidden="true" width="28" height="28" loading="lazy" decoding="async" onError={() => markLogoBroken(row.team.logo)} />
                                  </Show>
                                  <span class="team-name-text" title={row.team?.name || '未知球队'}>
                                    {row.team?.name || '未知球队'}
                                  </span>
                                </button>
                              </td>
                              <td>{row.played ?? '—'}</td>
                              <td>{row.win ?? '—'}</td>
                              <td>{row.draw ?? '—'}</td>
                              <td>{row.loss ?? '—'}</td>
                              <td>{row.goalDifference ?? '—'}</td>
                              <td>
                                <span class="form-strip" aria-label={row.recentForm || '暂无近期战绩'}>
                                  {row.recentForm || '—'}
                                </span>
                              </td>
                              <td>
                                <strong>{row.points ?? '—'}</strong>
                              </td>
                            </tr>
                          )}
                        </For>
                      </tbody>
                    </table>
                  </div>
                  <div class="standings-cards" aria-label="积分榜卡片视图">
                    <For each={standings()}>
                      {(row) => (
                        <button type="button" class="standing-card" aria-label={`查看${row.team?.name || '未知球队'}资料`} onClick={() => openTeam(row.team)}>
                          <div class="standing-card-top">
                            <span class={`rank-number ${rankClass(row)}`}>{row.rank}</span>
                            <Show when={row.zone}>
                              <span class={`zone-label zone-${String(row.zone).toLowerCase()}`}>{row.zoneLabel || zoneLabel(row.zone)}</span>
                            </Show>
                            <strong class="standing-card-name" title={row.team?.name || '未知球队'}>
                              {row.team?.name || '未知球队'}
                            </strong>
                            <span class="standing-card-pts">
                              <b>{row.points ?? '—'}</b> 积分
                            </span>
                          </div>
                          <div class="standing-card-stats">
                            <span>赛 {row.played ?? '—'}</span>
                            <span>胜 {row.win ?? '—'}</span>
                            <span>平 {row.draw ?? '—'}</span>
                            <span>负 {row.loss ?? '—'}</span>
                          </div>
                        </button>
                      )}
                    </For>
                  </div>
                </div>
              </Show>
              <Show when={!loading() && !loadError() && standings().length && standingDataState() === 'INCOMPLETE'}>
                <PageState title="积分数据尚未形成" description="当前只有参赛名单，没有可验证的积分、胜平负或净胜球数据；我们不会用一整页的 0 伪装成真实榜单。可先浏览赛程中的单场分析。" actionText="去看赛程与预测" onAction={goMatchesDiscover} />
              </Show>
              <Show when={!loading() && !loadError() && !standings().length}>
                <PageState title="该联赛暂无积分榜" description={qualityMessage() || '当前没有已同步的榜单数据。可切换联赛/赛季，稍后刷新，或先从赛程浏览单场分析。'} actionText="浏览赛程与预测" onAction={goMatchesDiscover} />
              </Show>
            </div>
          </section>

          <section class="ff-panel ff-page-section clubs-panel">
            <div class="ff-section-head">
              <div>
                <div class="ff-section-title">参赛俱乐部</div>
                <div class="ff-section-subtitle">从积分榜名单进入球队资料</div>
              </div>
              <div class="ff-section-actions">
                <input class="ff-input club-search" type="search" placeholder="搜索球队" aria-label="搜索球队" value={clubKeyword()} onInput={(e) => setClubKeyword(e.currentTarget.value)} />
              </div>
            </div>
            <div>
              <Show when={loading() && !filteredClubs().length}>
                <PageState type="loading" title="正在加载俱乐部..." />
              </Show>
              <Show when={!(loading() && !filteredClubs().length) && filteredClubs().length}>
                <div class="club-grid">
                  <For each={filteredClubs()}>
                    {(club) => (
                      <button type="button" class="club-card" title={club.name} aria-label={`查看${club.name}球队资料`} onClick={() => openTeam(club)}>
                        <Show
                          when={logoOk(club.logo)}
                          fallback={
                            <span class="club-logo-placeholder" title="暂无队徽" aria-label={`${club.name}暂无队徽`}>
                              {firstLetter(club.name)}
                            </span>
                          }
                        >
                          <img src={getMediaAssetUrl(club.logo)} alt="" aria-hidden="true" width="32" height="32" loading="lazy" decoding="async" onError={() => markLogoBroken(club.logo)} />
                        </Show>
                        <span class="club-name" title={club.name}>
                          {club.name}
                        </span>
                        <span class="club-meta">{clubRank(club) ? `第 ${clubRank(club)} 名` : '查看球队资料'}</span>
                        <span class="club-arrow" aria-hidden="true">
                          →
                        </span>
                      </button>
                    )}
                  </For>
                </div>
              </Show>
              <Show when={!(loading() && !filteredClubs().length) && !filteredClubs().length && clubKeyword().trim()}>
                <PageState title="没有符合条件的球队" description="换一个关键词，或清空搜索后再浏览参赛名单。" actionText="清除搜索" onAction={() => setClubKeyword('')} />
              </Show>
              <Show when={!(loading() && !filteredClubs().length) && !filteredClubs().length && !clubKeyword().trim()}>
                <PageState title="暂无俱乐部" description="积分榜同步后，参赛俱乐部会自动出现在这里。也可先从赛程页按球队名称搜索。" actionText="去赛程页搜索" onAction={goMatchesDiscover} />
              </Show>
            </div>
          </section>
        </div>

        <aside class="hub-predict-entry" aria-label="预测入口">
          <div>
            <strong>想看单场分析？</strong>
            <p>从比赛焦点进入精选预测，覆盖与模型质量会在预测页标明。</p>
          </div>
          <a class="hub-predict-link" href="/matches?discover=predict">
            浏览精选预测
          </a>
        </aside>
      </div>
    </div>
  )
}
