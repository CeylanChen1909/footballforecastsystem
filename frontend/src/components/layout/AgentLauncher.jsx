import { useNavigate } from '@solidjs/router'
import { t } from '../../i18n'

/** 助手悬浮球（旧版 AgentLauncher.vue 同款：固定右下角，点击进 /agent）。 */
export default function AgentLauncher() {
  const navigate = useNavigate()
  return (
    <button class="ff-agent-launcher" type="button" aria-label={t('agent.open')} onClick={() => navigate('/agent')}>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
        <path d="M21 12a8 8 0 0 1-8 8H4l2-3a8 8 0 1 1 15-5z" stroke-linejoin="round" />
      </svg>
      <span>{t('agent.label')}</span>
    </button>
  )
}
