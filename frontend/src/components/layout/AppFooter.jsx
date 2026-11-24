
import { t } from '../../i18n'

export default function AppFooter() {
  const year = new Date().getFullYear()
  return (
    <footer class="ff-footer" aria-label="站点页脚">
      <div class="ff-footer-inner">
        <div class="ff-footer-brand">
          <strong>ChenFootball</strong>
          <span>{t('footer.tagline')}</span>
        </div>
        <nav class="ff-footer-links" aria-label="页脚导航">
          <a href="/matches">{t('nav.matches')}</a>
          <a href="/matches?discover=predict">{t('footer.featured')}</a>
          <a href="/competitions">{t('nav.competitions')}</a>
          <a href="/agent">{t('nav.agent')}</a>
          <a href="/about">{t('nav.about')}</a>
          <a href="/terms">{t('terms.title')}</a>
          <a href="/privacy">{t('privacy.title')}</a>
        </nav>
        <p class="ff-footer-note">{t('footer.note')}</p>
        <p class="ff-footer-copy">© {year} ChenFootball</p>
      </div>
    </footer>
  )
}
