/**
 * AdminLogPanel（自 Vue src/views/admin/AdminLogPanel.vue 43 行移植）。
 *
 * 纯展示组件：日志列表与模块筛选由 AdminDashboard 持有。
 *  - el-table → .ff-admin-table（去 stripe、操作列不再 fixed="right"、
 *    移动端卡片化）；el-select → 原生 select；整行可点开详情（行点击 +
 *    查看按钮，按钮 stopPropagation 避免重复触发）。
 */
import { For, Show } from 'solid-js'
import PageState from '../../components/layout/PageState'
import '../../styles/admin.css'
import '../../styles/page-common.css'

export default function AdminLogPanel(props) {
  return (
    <div class="panel-card ff-panel">
      <div class="panel-header">
        <span>审计日志</span>
        <select
          class="ff-select"
          aria-label="模块筛选"
          style={{ width: '130px' }}
          value={props.moduleFilter || ''}
          onChange={(e) => {
            props.onModuleFilterChange?.(e.currentTarget.value)
            props.onFilterChange?.()
          }}
        >
          <option value="">全部模块</option>
          <option value="NEWS">NEWS</option>
          <option value="CONFIG">CONFIG</option>
        </select>
      </div>
      <Show when={props.error} fallback={
        <table class="ff-admin-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>操作人</th>
              <th>模块</th>
              <th>动作</th>
              <th>对象类型</th>
              <th>内容</th>
              <th>结果</th>
              <th style="text-align: right">操作</th>
            </tr>
          </thead>
          <tbody>
            <For each={props.logs} fallback={
              <tr>
                <td colspan="8">
                  <div class="ff-empty">暂无审计日志</div>
                </td>
              </tr>
            }>
              {(row) => (
                <tr class="is-clickable" onClick={() => props.onOpenDetail?.(row)}>
                  <td data-label="时间">{row.createdAt || '—'}</td>
                  <td data-label="操作人">{row.operatorName || '—'}</td>
                  <td data-label="模块">{row.module || '—'}</td>
                  <td data-label="动作">{row.action || '—'}</td>
                  <td data-label="对象类型">{row.targetType || '—'}</td>
                  <td data-label="内容">{row.content || '—'}</td>
                  <td data-label="结果">
                    <span class={`ff-tag ${row.result === 'SUCCESS' ? 'is-success' : 'is-danger'}`}>{row.result || '—'}</span>
                  </td>
                  <td data-label="操作" class="ff-table-actions">
                    <button
                      type="button"
                      class="ff-btn ff-btn-ghost ff-btn-sm"
                      onClick={(e) => {
                        e.stopPropagation()
                        props.onOpenDetail?.(row)
                      }}
                    >
                      查看
                    </button>
                  </td>
                </tr>
              )}
            </For>
          </tbody>
        </table>
      }>
        <PageState type="error" title="审计日志加载失败" description={props.error} actionText="重试" onAction={() => props.onRetry?.()} />
      </Show>
    </div>
  )
}
