/**
 * AdminUsersPanel（自 Vue src/views/admin/AdminUsersPanel.vue 65 行移植）。
 *
 * 纯展示组件：用户列表与筛选状态由 AdminDashboard 持有。
 *  - el-table → .ff-admin-table（去 stripe、操作列不再 fixed="right"、
 *    移动端卡片化）；el-select → 原生 select；el-dropdown → 手写 toggle 菜单
 *    （.ff-menu，见 page-common.css）。
 */
import { createSignal, For, Show } from 'solid-js'
import PageState from '../../components/layout/PageState'
import '../../styles/admin.css'
import '../../styles/page-common.css'

export default function AdminUsersPanel(props) {
  const [openMenuId, setOpenMenuId] = createSignal(null)
  const runCommand = (command, row) => {
    setOpenMenuId(null)
    props.onUserAction?.(command, row)
  }

  return (
    <div class="panel-card ff-panel">
      <div class="panel-header">
        <span>用户运营</span>
        <div class="panel-actions">
          <select
            class="ff-select"
            aria-label="角色筛选"
            style={{ width: '130px' }}
            value={props.roleFilter || ''}
            onChange={(e) => {
              props.onRoleFilterChange?.(e.currentTarget.value)
              props.onFilterChange?.()
            }}
          >
            <option value="">全部角色</option>
            <option value="USER">USER</option>
            <option value="ADMIN">ADMIN</option>
          </select>
          <select
            class="ff-select"
            aria-label="状态筛选"
            style={{ width: '130px' }}
            value={props.statusFilter || ''}
            onChange={(e) => {
              props.onStatusFilterChange?.(e.currentTarget.value)
              props.onFilterChange?.()
            }}
          >
            <option value="">全部状态</option>
            <option value="ACTIVE">ACTIVE</option>
            <option value="DISABLED">DISABLED</option>
          </select>
        </div>
      </div>
      <Show when={props.error} fallback={
        <table class="ff-admin-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>用户名</th>
              <th>角色</th>
              <th>状态</th>
              <th style="text-align: right">操作</th>
            </tr>
          </thead>
          <tbody>
            <For each={props.users} fallback={
              <tr>
                <td colspan="5">
                  <div class="ff-empty">暂无用户</div>
                </td>
              </tr>
            }>
              {(row) => (
                <tr>
                  <td data-label="ID">{row.id}</td>
                  <td data-label="用户名">{row.username}</td>
                  <td data-label="角色">
                    <span class={`ff-tag ${row.role === 'ADMIN' ? 'is-danger' : 'is-info'}`}>{row.role}</span>
                  </td>
                  <td data-label="状态">
                    <span class={`ff-tag ${row.status === 'ACTIVE' ? 'is-success' : 'is-warning'}`}>{row.status}</span>
                  </td>
                  <td data-label="操作" class="ff-table-actions">
                    <div class="ff-menu-wrap">
                      <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" aria-expanded={openMenuId() === row.id} onClick={() => setOpenMenuId(openMenuId() === row.id ? null : row.id)}>
                        更多操作 ▾
                      </button>
                      <Show when={openMenuId() === row.id}>
                        <div class="ff-menu" role="menu">
                          <button type="button" role="menuitem" onClick={() => runCommand('toggleRole', row)}>
                            切换角色
                          </button>
                          <button type="button" role="menuitem" onClick={() => runCommand('toggleStatus', row)}>
                            切换状态
                          </button>
                        </div>
                      </Show>
                    </div>
                  </td>
                </tr>
              )}
            </For>
          </tbody>
        </table>
      }>
        <PageState type="error" title="用户数据加载失败" description={props.error} actionText="重试" onAction={() => props.onRetry?.()} />
      </Show>
    </div>
  )
}
