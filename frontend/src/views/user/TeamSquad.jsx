/**
 * TeamSquad 页（自 Vue src/views/user/TeamSquad.vue 295 行移植）。
 *
 * 对齐要点（契约 §1.4/§2.3）：
 *  - params.teamId 只是球队名回退值；真正状态来自 query（name / teamId /
 *    logo / league / season），写出方是 Prediction goTeamSquad 与
 *    CompetitionHub openTeam。
 *  - 阵容 15 分钟 sessionStorage 缓存（键 chenfootball:squad:…，由
 *    squadCacheKey 生成）；getTeamSquad 用 preserveMeta，status / message /
 *    source / lastSyncedAt / cacheState 都在原始 payload 里。
 *  - 收藏：未登录弹 AuthDialog；已关注时并行删名/ID 双键遗留行；年龄 <18 只
 *    显示 N/A（隐私规则，照搬）。
 *  - el-select / el-input → 原生 select / input；el-tag → span.ff-tag。
 *  - Solid 2.0 注意：路由状态在 effect 的 compute 里取（不直读 searchParams），
 *    球员头像破损用 brokenPhotos 集合信号记（直接改 player 对象不触发重渲染）。
 */
import { createEffect, createMemo, createSignal, For, Show } from 'solid-js'
import { useLocation, useNavigate, useParams, useSearchParams } from '@solidjs/router'
import { crawlerApi, favoriteApi } from '../../api'
import auth from '../../stores/auth'
import { toastSuccess, toastWarning } from '../../utils/toast'
import { getMediaAssetUrl } from '../../utils/mediaAsset'
import PageSection from '../../components/layout/PageSection'
import PageState from '../../components/layout/PageState'
import '../../styles/squad.css'
import '../../styles/page-common.css'

const normalizeList = (value) => (Array.isArray(value) ? value : Array.isArray(value?.response) ? value.response : [])

export default function TeamSquad() {
  const params = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [teamName, setTeamName] = createSignal('')
  const [teamId, setTeamId] = createSignal('')
  const [league, setLeague] = createSignal('英超')
  const [season, setSeason] = createSignal('')
  const [loading, setLoading] = createSignal(false)
  const [error, setError] = createSignal('')
  const [teamLogo, setTeamLogo] = createSignal('')
  const [squad, setSquad] = createSignal([])
  const [squadStatus, setSquadStatus] = createSignal('UNKNOWN')
  const [squadMessage, setSquadMessage] = createSignal('')
  const [squadSource, setSquadSource] = createSignal('')
  const [syncedAt, setSyncedAt] = createSignal('')
  const [cacheState, setCacheState] = createSignal('')
  const [playerKeyword, setPlayerKeyword] = createSignal('')
  const [positionFilter, setPositionFilter] = createSignal('all')
  const [favorited, setFavorited] = createSignal(false)
  const [favorites, setFavorites] = createSignal([])
  const [brokenPhotos, setBrokenPhotos] = createSignal(new Set())

  // 注意：Solid 2.0 的 memo 创建时即求值，下面的 memo 会同步调用这些工具
  // 函数，因此它们必须定义在 memo 之前，否则 TDZ 崩溃整页
  // （2026-11-24 实测：Cannot access 'We' before initialization）。
  const positionKey = (value) => {
    const text = String(value || '')
    if (/goalkeeper|keeper|门将/i.test(text)) return 'goalkeeper'
    if (/defender|back|后卫/i.test(text)) return 'defender'
    if (/midfielder|中场/i.test(text)) return 'midfielder'
    if (/forward|attacker|striker|前锋/i.test(text)) return 'forward'
    return 'other'
  }
  const playerAgeLabel = (player) => {
    const age = Number(player?.age)
    if (!Number.isFinite(age) || age <= 0) return ''
    // Privacy: only surface ages for 18+ players; under-18 stays N/A.
    if (age < 18) return 'N/A'
    return `${Math.trunc(age)}岁`
  }
  const firstLetter = (value) => String(value || '?').trim().slice(0, 1).toUpperCase()
  const playerKey = (player) => `${player.id || player.name}-${player.number || ''}`
  const formatDateTime = (value) => {
    if (!value) return ''
    const date = new Date(value)
    return Number.isNaN(date.getTime())
      ? String(value).replace('T', ' ').slice(0, 16)
      : date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  }
  const normalizeFavoriteIdentity = (value) => String(value || '').trim().toLowerCase()
  const isCurrentFavorite = (item, ctx = null) => {
    const currentIds = [(ctx?.teamId ?? teamId()), (ctx?.name ?? teamName())].map(normalizeFavoriteIdentity).filter(Boolean)
    return [item?.teamId, item?.teamName].map(normalizeFavoriteIdentity).some((value) => value && currentIds.includes(value))
  }

  const seasonLabel = createMemo(() => season() || '当前赛季')
  const sourceLabel = createMemo(
    () =>
      ({ 'espn-squad': 'ESPN 公开球队页', 'espn-squad+transfermarkt-photos': 'ESPN 阵容 + Transfermarkt 头像', 'api-football': 'API-Football' }[
        squadSource()
      ] || squadSource() || '待确认'),
  )
  const syncedLabel = createMemo(() => formatDateTime(syncedAt()) || '待同步')
  const squadLabel = createMemo(() =>
    cacheState() === 'STALE'
      ? '最近快照较旧'
      : cacheState() === 'CACHED'
        ? '已使用最近快照'
        : { AVAILABLE: '已确认', EMPTY: '暂无名单', NOT_CONFIGURED: '未配置阵容源', REQUEST_FAILED: '请求失败', QUOTA_LIMITED: '额度受限' }[squadStatus()] || '待确认',
  )
  const squadTone = createMemo(() =>
    ['STALE', 'CACHED'].includes(cacheState())
      ? 'is-warning'
      : squadStatus() === 'AVAILABLE'
        ? 'is-success'
        : squadStatus() === 'EMPTY'
          ? 'is-info'
          : 'is-warning',
  )
  const squadGroups = createMemo(() => {
    const definitions = [
      { key: 'goalkeeper', label: '门将', test: /goalkeeper|keeper|门将/i },
      { key: 'defender', label: '后卫', test: /defender|back|后卫/i },
      { key: 'midfielder', label: '中场', test: /midfielder|中场/i },
      { key: 'forward', label: '前锋', test: /forward|attacker|striker|前锋/i },
    ]
    const groups = definitions.map((item) => ({ ...item, items: [] }))
    const other = { key: 'other', label: '其他 / 待确认', items: [] }
    squad().forEach((player) => (groups.find((group) => group.test.test(String(player.position || ''))) || other).items.push(player))
    return [...groups, other].filter((group) => group.items.length)
  })
  const filteredSquad = createMemo(() => {
    const keyword = playerKeyword().trim().toLowerCase()
    return squad().filter((player) => {
      const keywordOk =
        !keyword ||
        [player.name, player.position, player.nationality, player.number].some((value) => String(value || '').toLowerCase().includes(keyword))
      return keywordOk && (positionFilter() === 'all' || positionKey(player.position) === positionFilter())
    })
  })
  const filteredSquadGroups = createMemo(() => {
    const allowed = new Set(filteredSquad().map((player) => playerKey(player)))
    return squadGroups()
      .map((group) => ({ ...group, items: group.items.filter((player) => allowed.has(playerKey(player))) }))
      .filter((group) => group.items.length)
  })
  const favoriteRecords = createMemo(() => favorites().filter((item) => isCurrentFavorite(item)))

  // ctx 优先：路由 effect 里 set 后立即读信号是旧值（2.0 暂缓写入），
  // 缓存键与加载函数必须用本地值（2026-11-24 实测：loadSquad 读到空
  // teamName 直接返回，页面永远空态、连请求都不发）。
  const squadCacheKey = (ctx = null) => {
    const id = ctx?.teamId ?? teamId()
    const name = ctx?.name ?? teamName()
    const lg = ctx?.league ?? league()
    const sn = ctx?.season ?? season()
    return `chenfootball:squad:${id || name}:${lg}:${sn || 'current'}`
  }
  const applySquadPayload = (data) => {
    // 用本地 list 判断，避免 setSquad 后立即读 squad() 读到旧值（2.0 暂缓写入）。
    const list = normalizeList(data).map((player) => ({ ...player, photoBroken: false }))
    setSquad(list)
    setSquadStatus(data.status || (list.length ? 'AVAILABLE' : 'EMPTY'))
    setSquadMessage(data.message || '')
    setSquadSource(data.source || '')
    setSyncedAt(data.lastSyncedAt || '')
    setCacheState(data.cacheState || '')
  }

  const loadTeamLogo = async (ctx = null) => {
    const lg = ctx?.league ?? league()
    const name = ctx?.name ?? teamName()
    const tid = ctx?.teamId ?? teamId()
    if ((ctx ? ctx.logo : teamLogo()) || !lg) return
    try {
      const result = await crawlerApi.getTeamsByLeague(lg)
      const item = normalizeList(result).find(
        (team) =>
          String(team.name || '').toLowerCase() === name.toLowerCase() ||
          String(team.id || '') === tid ||
          String(team.teamId || '') === tid,
      )
      if (item?.logo) setTeamLogo(item.logo)
    } catch {
      // 队徽不是阵容主链路，失败时使用球队首字母占位。
    }
  }

  const loadSquad = async (force = false, ctx = null) => {
    const name = ctx?.name ?? teamName()
    const lg = ctx?.league ?? league()
    const tid = ctx?.teamId ?? teamId()
    const seasonText = ctx?.season ?? season()
    if (!name) return
    setLoading(true)
    setError('')
    setCacheState('')
    try {
      if (!force) {
        try {
          const cached = JSON.parse(sessionStorage.getItem(squadCacheKey(ctx)) || 'null')
          if (cached?.savedAt && Date.now() - Number(cached.savedAt) < 15 * 60 * 1000 && cached.payload) {
            applySquadPayload({ ...cached.payload, cacheState: cached.payload.cacheState || 'CACHED' })
            setLoading(false)
            return
          }
        } catch {
          /* ignore malformed browser cache */
        }
      }
      const squadResult = await crawlerApi.getTeamSquad(name, lg, tid, Number(String(seasonText).slice(0, 4)) || new Date().getFullYear(), {
        params: force ? { force: true } : {},
      })
      const data = squadResult?.data || squadResult || {}
      applySquadPayload(data)
      try {
        sessionStorage.setItem(squadCacheKey(ctx), JSON.stringify({ savedAt: Date.now(), payload: data }))
      } catch {
        /* storage may be disabled */
      }
    } catch (err) {
      setError(err?.message || '球队资料暂时不可用')
      setSquadStatus('REQUEST_FAILED')
      setSquadMessage('阵容请求失败，请稍后重试。')
    } finally {
      setLoading(false)
    }
  }

  const loadFavorites = async (ctx = null) => {
    if (!auth.token()) {
      setFavorites([])
      setFavorited(false)
      return
    }
    // 用本地 list 判断：setFavorites 后立即读 favoriteRecords() 是旧值。
    const list = await favoriteApi
      .list()
      .then((value) => (Array.isArray(value) ? value : value?.items || value?.data || []))
      .catch(() => [])
    setFavorites(list)
    setFavorited(list.filter((item) => isCurrentFavorite(item, ctx)).length > 0)
  }
  const toggleFavorite = async () => {
    if (!auth.token()) {
      auth.openAuthDialog(location.pathname + location.search)
      return
    }
    try {
      if (favorited() && favoriteRecords().length) {
        // Clean up both legacy name-keyed rows and newer provider-id rows so a
        // previous identity migration cannot leave the button stuck on “已关注”.
        await Promise.all(favoriteRecords().map((item) => favoriteApi.remove(item.teamId || item.teamName || teamName())))
        toastSuccess('已取消关注')
      } else {
        const id = teamId() || teamName()
        const result = await favoriteApi.add(id, teamName(), { teamLogo: teamLogo(), leagueName: league() })
        if (result?.ok === false) throw new Error(result.message || '关注保存失败')
        toastSuccess('已关注球队')
      }
      await loadFavorites()
    } catch (err) {
      toastWarning(err?.message || '关注操作失败')
    }
  }
  const backToCompetitions = () => navigate(`/competitions?league=${encodeURIComponent(league())}&season=${encodeURIComponent(season())}`)
  const goTeamMatches = () => navigate(`/matches?team=${encodeURIComponent(teamName())}&league=${encodeURIComponent(league())}`)
  const handlePlayerPhotoError = (player) => {
    // 头像源偶尔会因地区/CDN 缓存返回 404，立即切换到首字母头像，
    // 避免用户看到破图或反复重试。
    setBrokenPhotos((prev) => new Set(prev).add(playerKey(player)))
  }

  // 路由状态同步（含首次挂载）：query/params 在 compute 里取，回调只用本地值。
  createEffect(
    () => ({
      name: searchParams.name,
      teamId: searchParams.teamId,
      logo: searchParams.logo,
      league: searchParams.league,
      season: searchParams.season,
      param: params.teamId,
    }),
    (q) => {
      let name = String(q.name || q.param || '').trim()
      try {
        name = decodeURIComponent(name)
      } catch {
        /* keep raw */
      }
      setTeamName(name)
      // The route param is a name fallback, not a canonical favorite id. Using
      // it as the id made removal send an encoded team name while the row had
      // been stored under a provider id (or vice versa).
      const teamIdValue = String(q.teamId || '').trim()
      const logoValue = String(q.logo || '')
      const leagueValue = String(q.league || '英超')
      const seasonValue = String(q.season || '')
      setTeamId(teamIdValue)
      setTeamLogo(logoValue)
      setLeague(leagueValue)
      setSeason(seasonValue)
      // 用本地值直传：set 后立即读信号是旧值（Solid 2.0 暂缓写入），加载函数
      // 内部也读信号，因此把本地上下文传进去（否则 loadSquad 读到空队名
      // 直接返回，页面永远空态）。
      const ctx = { name, teamId: teamIdValue, logo: logoValue, league: leagueValue, season: seasonValue }
      const run = async () => {
        await Promise.all([loadSquad(false, ctx), loadFavorites(ctx), loadTeamLogo(ctx)])
      }
      run()
    },
  )
  // 登录态变化时刷新关注状态（Vue watch(userStore.token) 同款）。
  createEffect(
    () => auth.token(),
    () => {
      loadFavorites()
    },
  )

  return (
    <div class="squad-page">
      <div class="squad-main">
        <div class="page-toolbar">
          <button type="button" class="ff-btn ff-btn-ghost" onClick={backToCompetitions}>
            ← 返回赛事资料
          </button>
          <div class="toolbar-actions">
            <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" onClick={goTeamMatches}>
              查看赛程
            </button>
            <button type="button" class="ff-btn ff-btn-plain ff-btn-sm" disabled={loading()} onClick={() => loadSquad(true)}>
              刷新阵容
            </button>
          </div>
        </div>

        <Show when={loading() && !squad().length}>
          <PageState type="loading" title="正在加载球队阵容..." />
        </Show>
        <Show when={!loading() && error()}>
          <PageState type="error" title="球队阵容加载失败" description={error()} actionText="重试" onAction={() => loadSquad(true)} />
        </Show>
        <Show when={!(loading() && !squad().length) && !error()}>
          <section class="team-page-head ff-appear" aria-labelledby="team-page-title">
            <div class="team-identity">
              <Show
                when={teamLogo()}
                fallback={<span class="team-logo placeholder">{firstLetter(teamName())}</span>}
              >
                <img src={getMediaAssetUrl(teamLogo())} alt={teamName()} class="team-logo" onError={() => setTeamLogo('')} />
              </Show>
              <div>
                <span class="ff-kicker">
                  {league()} · {seasonLabel()}
                </span>
                <h1 id="team-page-title">{teamName()}</h1>
              </div>
            </div>
            <div class="hero-actions">
              <span class={`ff-tag ${squadTone()}`}>{squadLabel()}</span>
              <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" onClick={toggleFavorite}>
                {favorited() ? '已关注' : '关注球队'}
              </button>
            </div>
          </section>

          <section class="info-strip" aria-label="阵容数据说明">
            <div>
              <span>数据源</span>
              <strong>{sourceLabel()}</strong>
            </div>
            <div>
              <span>球员数</span>
              <strong>{squad().length || '—'}</strong>
            </div>
            <div>
              <span>最近同步</span>
              <strong>{syncedLabel()}</strong>
            </div>
            <div class="info-note">
              阵容来自公开球队页面，仅展示已抓取并可核验的名单，不用模型补全。
              <Show when={cacheState() === 'STALE'}>当前展示最近快照，后台正在更新。</Show>
              <Show when={cacheState() === 'CACHED'}>已使用最近快照；需要时可手动刷新。</Show>
            </div>
          </section>

          <PageSection title="注册阵容" subtitle={squadMessage() || '按位置查看当前公开注册名单'}>
            <Show when={squad().length}>
              <div class="squad-toolbar" aria-label="阵容筛选">
                <input class="ff-input" type="search" placeholder="搜索球员" aria-label="搜索球员" value={playerKeyword()} onInput={(e) => setPlayerKeyword(e.currentTarget.value)} />
                <select class="ff-select" aria-label="按位置筛选" value={positionFilter()} onChange={(e) => setPositionFilter(e.currentTarget.value)}>
                  <option value="all">全部位置</option>
                  <option value="goalkeeper">门将</option>
                  <option value="defender">后卫</option>
                  <option value="midfielder">中场</option>
                  <option value="forward">前锋</option>
                  <option value="other">其他 / 待确认</option>
                </select>
                <span>
                  显示 {filteredSquad().length} / {squad().length} 人
                </span>
              </div>
            </Show>
            <Show when={filteredSquad().length} fallback={
              <div class="empty-state">
                <span class="empty-mark">◎</span>
                <div>
                  <strong>{squad().length ? '没有匹配球员' : squadLabel()}</strong>
                  <p>{squad().length ? '换一个关键词或位置筛选后再试。' : squadMessage() || '当前公开阵容页面没有返回可核验的名单，请稍后重试。'}</p>
                  <Show when={!squad().length}>
                    <button type="button" class="ff-btn ff-btn-plain ff-btn-sm" disabled={loading()} onClick={() => loadSquad(true)}>
                      重新获取
                    </button>
                  </Show>
                </div>
              </div>
            }>
              <div class="squad-groups">
                <For each={filteredSquadGroups()}>
                  {(group) => (
                    <div class="squad-group">
                      <div class="group-heading">
                        <strong>{group.label}</strong>
                        <span>{group.items.length} 人</span>
                      </div>
                      <div class="player-grid">
                        <For each={group.items}>
                          {(player) => (
                            <article class="player-card">
                              <Show
                                when={player.photo && !brokenPhotos().has(playerKey(player))}
                                fallback={<span class="player-avatar">{firstLetter(player.name)}</span>}
                              >
                                <img src={getMediaAssetUrl(player.photo)} alt={player.name} onError={() => handlePlayerPhotoError(player)} />
                              </Show>
                              <div class="player-copy">
                                <strong>
                                  {player.name || '未知球员'}
                                  <Show when={player.number}>
                                    <b class="player-number">#{player.number}</b>
                                  </Show>
                                </strong>
                                <span>
                                  {player.position || '位置待确认'}
                                  <Show when={player.number}> · {player.number}号</Show>
                                </span>
                                <small>
                                  <Show when={player.nationality}>{player.nationality}</Show>
                                  <Show when={playerAgeLabel(player)}> · {playerAgeLabel(player)}</Show>
                                </small>
                              </div>
                            </article>
                          )}
                        </For>
                      </div>
                    </div>
                  )}
                </For>
              </div>
            </Show>
          </PageSection>
        </Show>
      </div>
    </div>
  )
}
