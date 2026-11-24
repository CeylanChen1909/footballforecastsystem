/**
 * MatchWorkbench（自 Vue src/views/admin/MatchWorkbench.vue 144 行移植）。
 *
 * 纯展示组件，状态全部由 AdminDashboard 持有并经 props/callbacks 传入：
 *  - el-date-picker / el-time-picker → 原生 date input；el-select → 原生 select；
 *    el-table → .ff-admin-table（去 stripe、操作列不再 fixed="right"、
 *    移动端卡片化，见 page-common.css）。
 *  - el-pagination → .ff-pager（总数 + 上一页/页码/下一页 + 每页条数）。
 *  - 队徽用 getMediaAssetUrl，破损时回退首字母占位（img onError 置空 src
 *    后由 Show 切到占位 span）。
 */
import { createMemo, createSignal, For, Show } from 'solid-js'
import { getMediaAssetUrl } from '../../utils/mediaAsset'
import PageState from '../../components/layout/PageState'
import '../../styles/admin.css'
import '../../styles/page-common.css'

const matchStatusTag = (status) => ({ LIVE: 'danger', FT: 'success', CANCEL: 'info', NS: 'warning' }[status] || 'info')

export default function MatchWorkbench(props) {
  const [brokenLogos, setBrokenLogos] = createSignal(new Set())
  const liveCount = createMemo(() => props.matches.filter((item) => ['LIVE', '1H', '2H', 'HT'].includes(item.status)).length)
  const upcomingCount = createMemo(() => props.matches.filter((item) => item.status === 'NS').length)
  const totalPages = createMemo(() => Math.max(1, Math.ceil((props.total || 0) / (props.pageSize || 20))))
  const logoOk = (logo) => Boolean(logo) && !brokenLogos().has(logo)
  const markLogoBroken = (logo) => {
    if (logo) setBrokenLogos((prev) => new Set(prev).add(logo))
  }
  const goPage = (page) => {
    const next = Math.max(1, Math.min(totalPages(), page))
    if (next !== props.page) props.onReload?.(next)
  }

  return (
    <div class="panel-card ff-panel">
      <div class="panel-header">
        <span>比赛工作台</span>
        <div class="panel-actions">
          <input
            class="ff-input"
            type="date"
            aria-label="筛选日期"
            style={{ width: '150px' }}
            value={props.date || ''}
            onInput={(e) => props.onDateChange?.(e.currentTarget.value)}
            onChange={() => props.onReload?.(1)}
          />
          <input
            class="ff-input"
            type="search"
            placeholder="搜索联赛/球队"
            aria-label="搜索联赛或球队"
            style={{ width: '200px' }}
            value={props.keyword || ''}
            onInput={(e) => props.onKeywordChange?.(e.currentTarget.value)}
            onChange={() => props.onReload?.(1)}
          />
          <select
            class="ff-select"
            aria-label="状态筛选"
            style={{ width: '140px' }}
            value={props.status || ''}
            onChange={(e) => {
              props.onStatusChange?.(e.currentTarget.value)
              props.onReload?.(1)
            }}
          >
            <option value="">全部状态</option>
            <option value="NS">未开始</option>
            <option value="LIVE">进行中</option>
            <option value="FT">已完场</option>
            <option value="CANCEL">已取消</option>
          </select>
          <button type="button" class="ff-btn ff-btn-primary" onClick={() => props.onCreate?.()}>
            新增维护项
          </button>
        </div>
      </div>

      <div class="workbench-summary">
        <div>
          <span>当前页</span>
          <strong>{props.matches.length}</strong>
        </div>
        <div>
          <span>进行中</span>
          <strong class="is-live">{liveCount()}</strong>
        </div>
        <div>
          <span>待开赛</span>
          <strong class="is-upcoming">{upcomingCount()}</strong>
        </div>
        <div class="summary-note">筛选条件会同步影响列表与分页</div>
      </div>

      <Show when={props.error} fallback={
        <table class="ff-admin-table">
          <thead>
            <tr>
              <th>Fixture ID</th>
              <th>External ID</th>
              <th>日期</th>
              <th>主队</th>
              <th>客队</th>
              <th>主比分</th>
              <th>客比分</th>
              <th>状态</th>
              <th style="text-align: right">操作</th>
            </tr>
          </thead>
          <tbody>
            <For each={props.matches} fallback={
              <tr>
                <td colspan="9">
                  <div class="ff-empty">暂无比赛</div>
                </td>
              </tr>
            }>
              {(row) => (
                <tr>
                  <td data-label="Fixture ID">{row.fixtureId ?? '—'}</td>
                  <td data-label="External ID">{row.externalMatchId || '—'}</td>
                  <td data-label="日期">{row.matchDate || '—'}</td>
                  <td data-label="主队">
                    <span class="admin-team-cell">
                      <Show
                        when={logoOk(row.homeTeamLogo)}
                        fallback={<span class="admin-team-logo">{(row.homeTeamName || 'H').slice(0, 1)}</span>}
                      >
                        <img src={getMediaAssetUrl(row.homeTeamLogo)} alt="" width="32" height="32" loading="lazy" onError={() => markLogoBroken(row.homeTeamLogo)} />
                      </Show>
                      {row.homeTeamName || '—'}
                    </span>
                  </td>
                  <td data-label="客队">
                    <span class="admin-team-cell">
                      <Show
                        when={logoOk(row.awayTeamLogo)}
                        fallback={<span class="admin-team-logo">{(row.awayTeamName || 'A').slice(0, 1)}</span>}
                      >
                        <img src={getMediaAssetUrl(row.awayTeamLogo)} alt="" width="32" height="32" loading="lazy" onError={() => markLogoBroken(row.awayTeamLogo)} />
                      </Show>
                      {row.awayTeamName || '—'}
                    </span>
                  </td>
                  <td data-label="主比分">{row.homeScore ?? '—'}</td>
                  <td data-label="客比分">{row.awayScore ?? '—'}</td>
                  <td data-label="状态">
                    <span class={`ff-tag is-${matchStatusTag(row.status)}`}>{row.status || 'UNKNOWN'}</span>
                  </td>
                  <td data-label="操作" class="ff-table-actions">
                    <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" onClick={() => props.onEdit?.(row)}>
                      编辑
                    </button>{' '}
                    <button type="button" class="ff-btn ff-btn-danger-ghost ff-btn-sm" onClick={() => props.onDelete?.(row)}>
                      删除
                    </button>
                  </td>
                </tr>
              )}
            </For>
          </tbody>
        </table>
      }>
        <PageState type="error" title="比赛数据加载失败" description={props.error} actionText="重试" onAction={() => props.onReload?.(props.page)} />
      </Show>

      <div class="ff-pager">
        <span>共 {props.total || 0} 条</span>
        <div class="ff-pager-pages">
          <button type="button" disabled={props.page <= 1} onClick={() => goPage(props.page - 1)}>
            上一页
          </button>
          <For each={Array.from({ length: totalPages() }, (_, i) => i + 1).filter((p) => p === 1 || p === totalPages() || Math.abs(p - props.page) <= 2)}>
            {(p) => (
              <button type="button" class={p === props.page ? 'is-active' : ''} onClick={() => goPage(p)}>
                {p}
              </button>
            )}
          </For>
          <button type="button" disabled={props.page >= totalPages()} onClick={() => goPage(props.page + 1)}>
            下一页
          </button>
        </div>
        <label>
          每页
          <select
            value={props.pageSize || 20}
            onChange={(e) => props.onSizeChange?.(Number(e.currentTarget.value))}
          >
            <option value="10">10</option>
            <option value="20">20</option>
            <option value="50">50</option>
            <option value="100">100</option>
          </select>
          条
        </label>
      </div>
    </div>
  )
}
