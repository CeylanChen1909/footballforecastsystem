/**
 * AdminConfigPanel（自 Vue src/views/admin/AdminConfigPanel.vue 94 行移植）。
 *
 * 纯展示组件：config 是 AdminDashboard 持有的 createStore 代理，面板只读
 * 不直接写——写操作经 onConfigChange(key, value) 回到 Dashboard。
 *  - el-descriptions → .ff-desc-list（见 admin.css 附录）；el-switch →
 *    .ff-switch（原生 checkbox 外观）；el-select / el-input → 原生控件。
 *  - 配置 API 历史上同时返回布尔与字符串 "true"/"false"，开关用字符串桥接
 *    （与原版 switchValue 同款逻辑）：读时 String(config[key] ?? 'false')，
 *    写时回写 'true'/'false' 字符串。
 */
import { For, Show } from 'solid-js'
import PageState from '../../components/layout/PageState'
import '../../styles/admin.css'
import '../../styles/page-common.css'

const textFields = [
  { key: 'agent.model.deepseek.base-url', label: 'DeepSeek Base URL', placeholder: '留空使用 DEEPSEEK_BASE_URL / 默认地址' },
  { key: 'agent.model.deepseek.model', label: 'DeepSeek 默认模型', placeholder: '留空使用 DEEPSEEK_MODEL' },
  { key: 'agent.model.deepseek.models', label: 'DeepSeek 模型白名单', placeholder: '多个模型用英文逗号分隔；留空不限制目录' },
  { key: 'agent.model.openrouter.base-url', label: 'OpenRouter Base URL', placeholder: '留空使用 OPENROUTER_BASE_URL / 默认地址' },
  { key: 'agent.model.openrouter.model', label: 'OpenRouter 默认模型', placeholder: '留空使用 OPENROUTER_MODEL' },
  { key: 'agent.model.openrouter.models', label: 'OpenRouter 模型白名单', placeholder: '多个模型用英文逗号分隔；留空使用内置目录' },
  { key: 'agent.model.scnet.base-url', label: 'SCNet Base URL', placeholder: 'https://api.scnet.cn/api/llm/v1' },
  { key: 'agent.model.scnet.model', label: 'SCNet 默认模型', placeholder: 'GLM-5-Base' },
  { key: 'agent.model.scnet.models', label: 'SCNet 模型白名单', placeholder: '多个模型用英文逗号分隔；留空使用 GLM-5-Base' },
  { key: 'agent.model.temperature', label: 'Temperature', placeholder: '0.4' },
  { key: 'agent.model.max-tokens', label: '最大输出 Tokens', placeholder: '1200' },
]

export default function AdminConfigPanel(props) {
  const switchChecked = (key) => String(props.config[key] ?? 'false') === 'true'
  const onSwitchChange = (key, checked) => props.onConfigChange?.(key, checked ? 'true' : 'false')

  return (
    <div class="panel-card ff-panel">
      <div class="panel-header">
        <span>系统配置</span>
        <button type="button" class="ff-btn ff-btn-primary" onClick={() => props.onSave?.()}>
          保存配置
        </button>
      </div>
      <Show when={props.error} fallback={
        <>
          <div class="ff-divider">Agent 全站模型策略</div>
          <div class="ff-alert is-info agent-config-note">
            <span>模型由管理员统一控制，普通用户不能切换。API Key 仍只从服务端环境变量读取，不会写入数据库或展示在页面。</span>
          </div>
          <dl class="ff-desc-list agent-config-descriptions">
            <div class="ff-desc-row">
              <dt>默认模型通道</dt>
              <dd>
                <select class="ff-select" aria-label="默认模型通道" value={props.config['agent.model.provider'] || ''} onChange={(e) => props.onConfigChange?.('agent.model.provider', e.currentTarget.value)}>
                  <option value="">自动选择（优先可用通道）</option>
                  <option value="auto">自动选择（优先可用通道）</option>
                  <option value="deepseek">DeepSeek</option>
                  <option value="openrouter">OpenRouter</option>
                  <option value="scnet">SCNet · GLM-5-Base</option>
                </select>
              </dd>
            </div>
            <For each={textFields}>
              {(field) => (
                <div class="ff-desc-row">
                  <dt>{field.label}</dt>
                  <dd>
                    <input class="ff-input" placeholder={field.placeholder} value={props.config[field.key] || ''} onInput={(e) => props.onConfigChange?.(field.key, e.currentTarget.value)} />
                  </dd>
                </div>
              )}
            </For>
            <div class="ff-desc-row">
              <dt>允许模型思考摘要</dt>
              <dd>
                <input type="checkbox" class="ff-switch" role="switch" aria-label="允许模型思考摘要" checked={switchChecked('agent.model.thinking.enabled')} onChange={(e) => onSwitchChange('agent.model.thinking.enabled', e.currentTarget.checked)} />
              </dd>
            </div>
            <div class="ff-desc-row">
              <dt>通道故障自动切换</dt>
              <dd>
                <input type="checkbox" class="ff-switch" role="switch" aria-label="通道故障自动切换" checked={switchChecked('agent.model.fallback.enabled')} onChange={(e) => onSwitchChange('agent.model.fallback.enabled', e.currentTarget.checked)} />
              </dd>
            </div>
          </dl>
        </>
      }>
        <PageState type="error" title="配置加载失败" description={props.error} actionText="重试" onAction={() => props.onRetry?.()} />
      </Show>
    </div>
  )
}
