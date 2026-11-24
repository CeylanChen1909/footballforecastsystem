/**
 * AdminChangelogPanel（自 Vue src/views/admin/AdminChangelogPanel.vue 215 行移植）。
 *
 * 自包含面板：onSettled 拉列表；编辑器用 Modal wide + 实时预览（标题/摘要
 * 必填手动校验，el-form rules 的 required 同款）；发布时间用原生
 * datetime-local（值与 "YYYY-MM-DD HH:mm" 互转）；删除用共享 ConfirmDialog
 * （仅返回 true 时执行）；el-table → .ff-admin-table；el-pagination →
 * .ff-pager。
 *  - Solid 2.0 注意：form 用 createStore，但 2.0 的 store setter 只有单
 *    函数形态 setState(fn)，1.x 的 setForm({...}) / setForm(key, value)
 *    会抛 "fn is not a function"（2026-11-24 实测），因此用
 *    utils/legacyStore 的 createLegacyStore（同名导入），调用点不变。
 */
import { createMemo, createSignal, For, Show, onSettled } from 'solid-js'
import { createLegacyStore as createStore } from '../../utils/legacyStore'
import { adminApi } from '../../api/admin'
import { toastError, toastSuccess } from '../../utils/toast'
import { createConfirm } from '../../components/ui/ConfirmDialog'
import Modal from '../../components/ui/Modal'
import PageState from '../../components/layout/PageState'
import '../../styles/admin.css'
import '../../styles/page-common.css'
import '../../styles/changelog.css'

const statusOptions = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'HIDDEN', label: '已下线' },
  { value: 'ARCHIVED', label: '已归档' },
]
const toneOptions = [
  { value: 'account', label: '账户（紫色）' },
  { value: 'match', label: '赛程（绿色）' },
  { value: 'prediction', label: '预测（橙色）' },
  { value: 'system', label: '系统（灰色）' },
]
const statusLabel = (value) => statusOptions.find((item) => item.value === value)?.label || value || '未知'
const statusType = (value) => ({ PUBLISHED: 'success', DRAFT: 'info', HIDDEN: 'warning', ARCHIVED: '' }[value] || 'info')
const toInputDatetime = (value) => String(value || '').replace(' ', 'T').slice(0, 16)
const fromInputDatetime = (value) => String(value || '').replace('T', ' ')

export default function AdminChangelogPanel() {
  const { ask: askConfirm, ConfirmDialog } = createConfirm()
  const [items, setItems] = createSignal([])
  const [total, setTotal] = createSignal(0)
  const [page, setPage] = createSignal(1)
  const [size, setSize] = createSignal(20)
  const [keyword, setKeyword] = createSignal('')
  const [status, setStatus] = createSignal('')
  const [loading, setLoading] = createSignal(false)
  const [saving, setSaving] = createSignal(false)
  const [error, setError] = createSignal('')
  const [dialogVisible, setDialogVisible] = createSignal(false)
  const [editingId, setEditingId] = createSignal(null)
  const [form, setForm] = createStore({ title: '', summary: '', detailsText: '', tag: '赛程', tone: 'match', versionLabel: '', status: 'DRAFT', publishAt: '' })
  const totalPages = createMemo(() => Math.max(1, Math.ceil((total() || 0) / (size() || 20))))
  const previewDetails = createMemo(() => String(form.detailsText || '').split(/\r?\n/).map((item) => item.trim()).filter(Boolean).slice(0, 8))

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const result = await adminApi.listChangelog({ keyword: keyword().trim() || undefined, status: status() || undefined, page: page(), size: size() })
      setItems(Array.isArray(result?.items) ? result.items : [])
      setTotal(Number(result?.total || 0))
    } catch (err) {
      setItems([])
      setTotal(0)
      setError(err?.message || '更新日志加载失败')
    } finally {
      setLoading(false)
    }
  }
  const resetFilters = () => {
    setKeyword('')
    setStatus('')
    setPage(1)
    load()
  }
  const resetForm = () => setForm({ title: '', summary: '', detailsText: '', tag: '赛程', tone: 'match', versionLabel: '', status: 'DRAFT', publishAt: '' })
  const openCreate = () => {
    setEditingId(null)
    resetForm()
    setDialogVisible(true)
  }
  const openEdit = (row) => {
    setEditingId(row.id)
    setForm({
      title: row.title || '',
      summary: row.summary || '',
      detailsText: row.detailsText || (row.details || []).join('\n'),
      tag: row.tag || '赛程',
      tone: row.tone || 'match',
      versionLabel: row.versionLabel || '',
      status: row.status || 'DRAFT',
      publishAt: row.publishAt || '',
    })
    setDialogVisible(true)
  }
  const save = async () => {
    // 手动校验（原版 el-form rules：标题/摘要必填）。
    if (!form.title.trim()) {
      toastError('请输入标题')
      return
    }
    if (!form.summary.trim()) {
      toastError('请输入摘要')
      return
    }
    setSaving(true)
    try {
      await adminApi.saveChangelog({ ...form, id: editingId() || undefined })
      toastSuccess(editingId() ? '更新日志已保存' : '更新日志已创建')
      setDialogVisible(false)
      await load()
    } catch {
      /* interceptor 已提示错误 */
    } finally {
      setSaving(false)
    }
  }
  const toggleStatus = async (row) => {
    const next = row.status === 'PUBLISHED' ? 'HIDDEN' : 'PUBLISHED'
    try {
      await adminApi.setChangelogStatus(row.id, next)
      toastSuccess(next === 'PUBLISHED' ? '日志已发布' : '日志已下线')
      await load()
    } catch {
      /* interceptor 已提示错误 */
    }
  }
  const remove = async (row) => {
    const ok = await askConfirm({ title: '删除更新日志', message: `确定删除“${row.title}”吗？删除后不可恢复。`, confirmText: '删除', cancelText: '取消' })
    if (ok !== true) return
    try {
      await adminApi.deleteChangelog(row.id)
      toastSuccess('更新日志已删除')
      if (items().length === 1 && page() > 1) setPage((p) => p - 1)
      await load()
    } catch (err) {
      toastError(err?.message || '删除失败')
    }
  }
  const goPage = (next) => {
    const target = Math.max(1, Math.min(totalPages(), next))
    if (target !== page()) {
      setPage(target)
      load()
    }
  }

  onSettled(() => {
    load()
  })

  return (
    <section class="changelog-admin-panel">
      <div class="panel-card ff-panel">
        <div class="panel-header">
          <div>
            <strong>更新日志</strong>
            <small>管理比赛页顶部展示的版本变化、功能更新和维护说明</small>
          </div>
          <div class="panel-actions">
            <button type="button" class="ff-btn ff-btn-ghost" disabled={loading()} onClick={load}>
              刷新
            </button>
            <button type="button" class="ff-btn ff-btn-primary" onClick={openCreate}>
              新建日志
            </button>
          </div>
        </div>

        <div class="changelog-toolbar">
          <input
            class="ff-input"
            type="search"
            placeholder="搜索标题、摘要或标签"
            aria-label="搜索更新日志"
            value={keyword()}
            onInput={(e) => setKeyword(e.currentTarget.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') load()
            }}
          />
          <select
            class="ff-select"
            aria-label="状态筛选"
            value={status()}
            onChange={(e) => {
              setStatus(e.currentTarget.value)
              load()
            }}
          >
            <option value="">全部状态</option>
            <For each={statusOptions}>{(item) => <option value={item.value}>{item.label}</option>}</For>
          </select>
          <button type="button" class="ff-btn ff-btn-ghost" onClick={resetFilters}>
            重置
          </button>
        </div>

        <Show when={error()} fallback={
          <Show when={loading() && !items().length} fallback={
            <table class="ff-admin-table">
              <thead>
                <tr>
                  <th>日志</th>
                  <th>标签</th>
                  <th>状态</th>
                  <th>发布时间</th>
                  <th>最近修改</th>
                  <th style="text-align: right">操作</th>
                </tr>
              </thead>
              <tbody>
                <For each={items()} fallback={
                  <tr>
                    <td colspan="6">
                      <div class="ff-empty">暂无更新日志</div>
                    </td>
                  </tr>
                }>
                  {(row) => (
                    <tr>
                      <td data-label="日志">
                        <div class="entry-title-cell">
                          <strong>{row.title}</strong>
                          <span>{row.summary}</span>
                          <Show when={row.versionLabel}>
                            <small>{row.versionLabel}</small>
                          </Show>
                        </div>
                      </td>
                      <td data-label="标签">{row.tag || '—'}</td>
                      <td data-label="状态">
                        <span class={`ff-tag is-${statusType(row.status)}`}>{statusLabel(row.status)}</span>
                      </td>
                      <td data-label="发布时间">{row.publishAt || '未安排'}</td>
                      <td data-label="最近修改">{row.updatedAt || '—'}</td>
                      <td data-label="操作" class="ff-table-actions">
                        <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" onClick={() => openEdit(row)}>
                          编辑
                        </button>{' '}
                        <button type="button" class={`ff-btn ff-btn-sm ${row.status === 'PUBLISHED' ? 'ff-btn-warning' : 'ff-btn-plain'}`} onClick={() => toggleStatus(row)}>
                          {row.status === 'PUBLISHED' ? '下线' : '发布'}
                        </button>{' '}
                        <button type="button" class="ff-btn ff-btn-danger-ghost ff-btn-sm" onClick={() => remove(row)}>
                          删除
                        </button>
                      </td>
                    </tr>
                  )}
                </For>
              </tbody>
            </table>
          }>
            <PageState type="loading" title="正在加载更新日志…" />
          </Show>
        }>
          <PageState type="error" title="更新日志加载失败" description={error()} actionText="重试" onAction={load} />
        </Show>

        <div class="ff-pager">
          <span>共 {total()} 条</span>
          <div class="ff-pager-pages">
            <button type="button" disabled={page() <= 1} onClick={() => goPage(page() - 1)}>
              上一页
            </button>
            <For each={Array.from({ length: totalPages() }, (_, i) => i + 1).filter((p) => p === 1 || p === totalPages() || Math.abs(p - page()) <= 2)}>
              {(p) => (
                <button type="button" class={p === page() ? 'is-active' : ''} onClick={() => goPage(p)}>
                  {p}
                </button>
              )}
            </For>
            <button type="button" disabled={page() >= totalPages()} onClick={() => goPage(page() + 1)}>
              下一页
            </button>
          </div>
          <label>
            每页
            <select
              value={size()}
              onChange={(e) => {
                setSize(Number(e.currentTarget.value))
                setPage(1)
                load()
              }}
            >
              <option value="10">10</option>
              <option value="20">20</option>
              <option value="50">50</option>
            </select>
            条
          </label>
        </div>
      </div>

      <Modal open={dialogVisible()} title={editingId() ? '编辑更新日志' : '新建更新日志'} wide onClose={() => setDialogVisible(false)}>
        <div class="editor-layout">
          <form
            class="changelog-form"
            onSubmit={(e) => {
              e.preventDefault()
              save()
            }}
          >
            <div class="form-grid">
              <div class="ff-form-item">
                <label for="changelog-title">标题</label>
                <input id="changelog-title" class="ff-input" maxlength="160" placeholder="例如：比赛焦点推荐上线" value={form.title} onInput={(e) => setForm('title', e.currentTarget.value)} />
              </div>
              <div class="ff-form-item">
                <label for="changelog-version">版本标识</label>
                <input id="changelog-version" class="ff-input" maxlength="64" placeholder="例如 v1.8.0（可选）" value={form.versionLabel} onInput={(e) => setForm('versionLabel', e.currentTarget.value)} />
              </div>
            </div>
            <div class="ff-form-item">
              <label for="changelog-summary">摘要</label>
              <textarea id="changelog-summary" class="ff-textarea" rows="2" maxlength="500" placeholder="一句话说明这次更新对用户有什么影响" value={form.summary} onInput={(e) => setForm('summary', e.currentTarget.value)} />
            </div>
            <div class="form-grid form-grid-three">
              <div class="ff-form-item">
                <label for="changelog-tag">标签</label>
                <input id="changelog-tag" class="ff-input" maxlength="32" placeholder="赛程 / 预测 / 账户" value={form.tag} onInput={(e) => setForm('tag', e.currentTarget.value)} />
              </div>
              <div class="ff-form-item">
                <label for="changelog-tone">色彩语义</label>
                <select id="changelog-tone" class="ff-select" value={form.tone} onChange={(e) => setForm('tone', e.currentTarget.value)}>
                  <For each={toneOptions}>{(item) => <option value={item.value}>{item.label}</option>}</For>
                </select>
              </div>
              <div class="ff-form-item">
                <label for="changelog-status">状态</label>
                <select id="changelog-status" class="ff-select" value={form.status} onChange={(e) => setForm('status', e.currentTarget.value)}>
                  <For each={statusOptions}>{(item) => <option value={item.value}>{item.label}</option>}</For>
                </select>
              </div>
            </div>
            <div class="ff-form-item">
              <label for="changelog-publish">发布时间</label>
              <input
                id="changelog-publish"
                class="ff-input"
                type="datetime-local"
                value={toInputDatetime(form.publishAt)}
                onChange={(e) => setForm('publishAt', fromInputDatetime(e.currentTarget.value))}
              />
              <small class="form-hint">立即发布可留空</small>
            </div>
            <div class="ff-form-item">
              <label for="changelog-details">详细更新（每行一条）</label>
              <textarea id="changelog-details" class="ff-textarea" rows="7" maxlength="5000" placeholder={'例如：\n焦点比赛根据联赛和球队热度计算\n支持中英文球队名切换'} value={form.detailsText} onInput={(e) => setForm('detailsText', e.currentTarget.value)} />
            </div>
          </form>

          <aside class="entry-preview" aria-label="更新日志预览">
            <span class="preview-kicker">PREVIEW</span>
            <div class="preview-meta">
              <time>{form.publishAt || '待发布'}</time>
              <span class={`is-${form.tone}`}>{form.tag || '标签'}</span>
            </div>
            <h3>{form.title || '更新日志标题'}</h3>
            <p>{form.summary || '摘要会显示在这里。'}</p>
            <Show when={previewDetails().length}>
              <ul>
                <For each={previewDetails()}>{(item) => <li>{item}</li>}</For>
              </ul>
            </Show>
          </aside>
        </div>
        <div class="ff-modal-actions">
          <button type="button" class="ff-btn ff-btn-ghost" onClick={() => setDialogVisible(false)}>
            取消
          </button>
          <button type="button" class="ff-btn ff-btn-primary" disabled={saving()} onClick={save}>
            保存日志
          </button>
        </div>
      </Modal>
      <ConfirmDialog />
    </section>
  )
}
