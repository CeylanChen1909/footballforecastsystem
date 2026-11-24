/**
 * 隐私横幅（旧版 ConsentBanner.vue 同款行为）。
 * 未表态时出现；“允许匿名统计”/“仅必要功能”写入本地授权并关闭。
 */
import { createSignal, onSettled, Show } from 'solid-js'

import { getAnalyticsConsent, setAnalyticsConsent } from '../../utils/privacyConsent'
import { t } from '../../i18n'

export default function ConsentBanner() {
  const [visible, setVisible] = createSignal(false)
  const sync = () => setVisible(!getAnalyticsConsent())
  const choose = (value) => {
    setAnalyticsConsent(value)
    setVisible(false)
  }
  // Solid 2.0：onMount 已删，onSettled 是挂载后一次性工作的替代（可返回 cleanup）。
  onSettled(() => {
    sync()
    window.addEventListener('football-consent-changed', sync)
    return () => window.removeEventListener('football-consent-changed', sync)
  })
  return (
    <Show when={visible()}>
      <aside class="ff-consent-banner" role="region" aria-live="polite" aria-label="隐私提示">
        <div>
          <strong>{t('consent.title')}</strong>
          <p>{t('consent.body')}</p>
        </div>
        <div class="ff-consent-actions">
          <a href="/privacy" class="ff-consent-link" onClick={() => setVisible(false)}>
            {t('nav.privacy')}
          </a>
          <button type="button" class="ff-consent-secondary" onClick={() => choose('essential')}>
            {t('consent.essential')}
          </button>
          <button type="button" class="ff-consent-primary" onClick={() => choose('granted')}>
            {t('consent.grant')}
          </button>
        </div>
      </aside>
    </Show>
  )
}
