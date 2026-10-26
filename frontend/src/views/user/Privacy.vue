<template>
  <div class="privacy-page ff-page-shell">
    <AppTopNav title="ChenFootball" :subtitle="t('privacy.subtitle')" :brand-icon="Football" active-path="/privacy" />
    <main id="app-main" class="privacy-main" tabindex="-1">
      <p>{{ t('privacy.intro') }}</p>
      <nav class="privacy-toc" :aria-label="t('privacy.tocAria')"><a href="#privacy-data">{{ t('privacy.tocData') }}</a><a href="#privacy-retention">{{ t('privacy.tocRetention') }}</a><a href="#privacy-cookie">{{ t('privacy.tocCookies') }}</a><a href="#privacy-third-party">{{ t('privacy.tocThirdParty') }}</a><a href="#privacy-rights">{{ t('privacy.tocRights') }}</a></nav>
      <h2 id="privacy-data">{{ t('privacy.dataTitle') }}</h2>
      <p>{{ t('privacy.dataBody') }}</p>
      <h2 id="privacy-retention">{{ t('privacy.retentionTitle') }}</h2>
      <p>{{ t('privacy.retentionBody') }}</p>
      <h2 id="privacy-cookie">{{ t('privacy.cookieTitle') }}</h2>
      <p>{{ t('privacy.cookieBody') }}</p>
      <h2 id="privacy-third-party">{{ t('privacy.thirdPartyTitle') }}</h2>
      <p>{{ t('privacy.thirdPartyBody') }}</p>
      <h2 id="privacy-rights">{{ t('privacy.rightsTitle') }}</h2>
      <p>{{ t('privacy.rightsBody') }}</p>
      <div class="privacy-actions"><el-button type="primary" plain @click="$router.back()">{{ t('common.back') }}</el-button><el-button plain @click="resetConsent">{{ t('privacy.resetConsent') }}</el-button></div>
    </main>
      <AppFooter />
  </div>
</template>

<script setup>
import AppFooter from '../../components/layout/AppFooter.vue'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import { Football } from '@element-plus/icons-vue'
import { clearAnalyticsConsent } from '../../utils/privacyConsent'
import { useI18n } from '../../i18n'

const { t } = useI18n()
const resetConsent = () => clearAnalyticsConsent()
</script>

<style scoped>
.privacy-main { max-width: 820px; margin: 0 auto; padding: 48px 24px; color: var(--ff-text); line-height: 1.85; }
.privacy-title-row { display:flex; align-items:flex-end; justify-content:space-between; gap:20px; margin-bottom:24px; }.privacy-title-row h1 { margin:8px 0 0; }.privacy-title-row small { color:var(--ff-text-faint); font:11px var(--ff-mono); white-space:nowrap; }
.privacy-main h1 { margin-bottom: 24px; color: var(--ff-ink); }
.privacy-main h2 { margin: 26px 0 8px; color: var(--ff-primary); font-size: 18px; }
.privacy-main p { color: var(--ff-text-muted); }
.privacy-actions { display:flex; flex-wrap:wrap; gap:8px; margin-top:28px; }
.privacy-toc { display:flex; flex-wrap:wrap; gap:6px; margin:22px 0 28px; padding:10px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-soft); }
.privacy-toc a { padding:6px 9px; border-radius:999px; color:var(--ff-primary); background:var(--ff-primary-soft); font-size:11px; }
.privacy-toc a:hover { text-decoration: underline; text-underline-offset: 2px; }
.privacy-main h2 { scroll-margin-top: 84px; }
@media (max-width: 680px) { .privacy-main { padding:28px 16px 40px; } .privacy-title-row { align-items:flex-start; flex-direction:column; gap:8px; } }
</style>
