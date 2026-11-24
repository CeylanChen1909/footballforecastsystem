import PageSection from '../../components/layout/PageSection'
import PageState from '../../components/layout/PageState'
import { useNavigate } from '@solidjs/router'
import { t } from '../../i18n'
import '../../styles/notfound.css'

export default function NotFound() {
  const navigate = useNavigate()
  return (
    <div class="not-found-page">
      <main id="app-main" class="not-found-main" tabindex="-1">
        <PageState type="empty" title={t('common.notFoundTitle')} description={t('common.notFoundDesc')} actionText={t('common.browseMatches')} onAction={() => navigate('/matches')} />
        <div class="not-found-links">
          <button type="button" class="ff-btn ff-btn-ghost" onClick={() => navigate('/competitions')}>
            赛事资料
          </button>
          <button type="button" class="ff-btn ff-btn-ghost" onClick={() => navigate('/agent')}>
            助手
          </button>
          <button type="button" class="ff-btn ff-btn-ghost" onClick={() => navigate('/privacy')}>
            隐私说明
          </button>
        </div>
      </main>
    </div>
  )
}
