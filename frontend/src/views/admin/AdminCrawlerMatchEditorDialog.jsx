/**
 * AdminCrawlerMatchEditorDialog（自 Vue src/views/admin/AdminCrawlerMatchEditorDialog.vue 57 行移植）。
 *
 * form 是 AdminDashboard 持有的 createStore 代理，编辑直接写回 Dashboard
 * 的表单（与原版 v-model 同款）；保存前手动校验（原版 el-form rules）：
 *  - 必填：比赛日期 / 比赛时间 / 联赛 / 主队名称 / 客队名称 / 状态；
 *  - logo 必须是 http(s) 地址（可空）；
 *  - 比分成对且为非负整数（只填一边报错）。
 *  - el-date-picker / el-time-picker → 原生 date / time input（time 用
 *    step="1" 支持秒，与原版 value-format HH:mm:ss 对齐）。
 * 校验失败用 toastError 提示第一条，不触发 onSave。
 */
import { createSignal, Show } from 'solid-js'
import { toastError } from '../../utils/toast'
import Modal from '../../components/ui/Modal'
import '../../styles/admin.css'
import '../../styles/page-common.css'

const isValidUrl = (value) => {
  if (!value) return true
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol)
  } catch {
    return false
  }
}

export default function AdminCrawlerMatchEditorDialog(props) {
  const [formError, setFormError] = createSignal('')
  const form = () => props.form

  const validate = () => {
    const f = form()
    if (!String(f.matchDate || '').trim()) return '请输入比赛日期'
    if (!String(f.matchTime || '').trim()) return '请输入比赛时间'
    if (!String(f.leagueName || '').trim()) return '请输入联赛'
    if (!String(f.homeTeamName || '').trim()) return '请输入主队名称'
    if (!String(f.awayTeamName || '').trim()) return '请输入客队名称'
    if (!String(f.status || '').trim()) return '请选择状态'
    if (!isValidUrl(f.homeTeamLogo)) return '主队 Logo 请输入有效的 http(s) 地址'
    if (!isValidUrl(f.awayTeamLogo)) return '客队 Logo 请输入有效的 http(s) 地址'
    const filled = [f.homeScore, f.awayScore].filter((item) => item !== '' && item !== null && item !== undefined)
    if (filled.length === 1) return '请同时填写主队和客队比分'
    if (filled.some((item) => !/^\d+$/.test(String(item)))) return '比分必须为非负整数'
    return ''
  }
  const submit = () => {
    const error = validate()
    setFormError(error)
    if (error) {
      toastError(error)
      return
    }
    props.onSave?.()
  }

  return (
    <Modal open={props.open} title={form().id ? '编辑爬虫比赛' : '新增爬虫比赛'} wide onClose={() => props.onClose?.()}>
      <form
        class="form-grid"
        onSubmit={(e) => {
          e.preventDefault()
          submit()
        }}
      >
        <div class="ff-form-item">
          <label>Fixture ID</label>
          <input class="ff-input" value={form().fixtureId ?? ''} disabled />
        </div>
        <div class="ff-form-item">
          <label for="crawler-external-id">External ID</label>
          <input id="crawler-external-id" class="ff-input" placeholder="主爬虫事件 ID，用于详情和预测关联" value={form().externalMatchId || ''} onInput={(e) => props.onFormChange?.('externalMatchId', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-source">数据来源</label>
          <input id="crawler-source" class="ff-input" placeholder="bbc-scores" value={form().source || ''} onInput={(e) => props.onFormChange?.('source', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-date">比赛日期</label>
          <input id="crawler-date" class="ff-input" type="date" value={form().matchDate || ''} onInput={(e) => props.onFormChange?.('matchDate', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-time">比赛时间</label>
          <input id="crawler-time" class="ff-input" type="time" step="1" value={form().matchTime || ''} onInput={(e) => props.onFormChange?.('matchTime', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-league">联赛</label>
          <input id="crawler-league" class="ff-input" value={form().leagueName || ''} onInput={(e) => props.onFormChange?.('leagueName', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-league-id">联赛ID</label>
          <input id="crawler-league-id" class="ff-input" value={form().leagueId || ''} onInput={(e) => props.onFormChange?.('leagueId', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-round">轮次</label>
          <input id="crawler-round" class="ff-input" value={form().round || ''} onInput={(e) => props.onFormChange?.('round', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-venue">场馆</label>
          <input id="crawler-venue" class="ff-input" value={form().venue || ''} onInput={(e) => props.onFormChange?.('venue', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-home-name">主队</label>
          <input id="crawler-home-name" class="ff-input" value={form().homeTeamName || ''} onInput={(e) => props.onFormChange?.('homeTeamName', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-home-id">主队ID</label>
          <input id="crawler-home-id" class="ff-input" value={form().homeTeamId || ''} onInput={(e) => props.onFormChange?.('homeTeamId', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-home-logo">主队Logo</label>
          <input id="crawler-home-logo" class="ff-input" placeholder="https://..." value={form().homeTeamLogo || ''} onInput={(e) => props.onFormChange?.('homeTeamLogo', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-away-name">客队</label>
          <input id="crawler-away-name" class="ff-input" value={form().awayTeamName || ''} onInput={(e) => props.onFormChange?.('awayTeamName', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-away-id">客队ID</label>
          <input id="crawler-away-id" class="ff-input" value={form().awayTeamId || ''} onInput={(e) => props.onFormChange?.('awayTeamId', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-away-logo">客队Logo</label>
          <input id="crawler-away-logo" class="ff-input" placeholder="https://..." value={form().awayTeamLogo || ''} onInput={(e) => props.onFormChange?.('awayTeamLogo', e.currentTarget.value)} />
        </div>
        <div class="ff-form-item">
          <label for="crawler-status">状态</label>
          <select id="crawler-status" class="ff-select" value={form().status || 'NS'} onChange={(e) => props.onFormChange?.('status', e.currentTarget.value)}>
            <option value="NS">SCHEDULED</option>
            <option value="LIVE">LIVE</option>
            <option value="FT">FINISHED</option>
            <option value="CANCEL">CANCELED</option>
          </select>
        </div>
        <div class="ff-form-item">
          <label>比分</label>
          <div class="score-row">
            <input class="ff-input" placeholder="主队" value={form().homeScore ?? ''} onInput={(e) => props.onFormChange?.('homeScore', e.currentTarget.value)} />
            <span>-</span>
            <input class="ff-input" placeholder="客队" value={form().awayScore ?? ''} onInput={(e) => props.onFormChange?.('awayScore', e.currentTarget.value)} />
          </div>
        </div>
        <div class="ff-form-item span-2">
          <label for="crawler-note">备注</label>
          <textarea id="crawler-note" class="ff-textarea" rows="4" value={form().note || ''} onInput={(e) => props.onFormChange?.('note', e.currentTarget.value)} />
        </div>
      </form>
      <Show when={formError()}>
        <div class="ff-alert is-warning">
          <span>{formError()}</span>
        </div>
      </Show>
      <div class="ff-modal-actions">
        <button type="button" class="ff-btn ff-btn-ghost" onClick={() => props.onClose?.()}>
          取消
        </button>
        <button type="button" class="ff-btn ff-btn-primary" onClick={submit}>
          保存
        </button>
      </div>
    </Modal>
  )
}
