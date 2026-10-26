<template>
  <div class="legal-page ff-page-shell">
    <AppTopNav title="ChenFootball" :subtitle="t('terms.subtitle')" :brand-icon="Football" active-path="/terms" />
    <main id="app-main" class="legal-main" tabindex="-1">
      <p class="legal-lead">{{ t('terms.lead') }}</p>
      <nav class="legal-toc" :aria-label="t('terms.tocAria')">
        <a href="#terms-service">{{ t('terms.tocService') }}</a>
        <a href="#terms-account">{{ t('terms.tocAccount') }}</a>
        <a href="#terms-content">{{ t('terms.tocContent') }}</a>
        <a href="#terms-acceptable">{{ t('terms.tocAcceptable') }}</a>
        <a href="#terms-liability">{{ t('terms.tocLiability') }}</a>
        <a href="#terms-changes">{{ t('terms.tocChanges') }}</a>
      </nav>
      <h2 id="terms-service">{{ t('terms.serviceTitle') }}</h2>
      <p>{{ t('terms.serviceBody') }}</p>
      <h2 id="terms-account">{{ t('terms.accountTitle') }}</h2>
      <p>{{ t('terms.accountBody') }}</p>
      <h2 id="terms-content">{{ t('terms.contentTitle') }}</h2>
      <p>{{ t('terms.contentBody') }}</p>
      <h2 id="terms-acceptable">{{ t('terms.acceptableTitle') }}</h2>
      <p>{{ t('terms.acceptableBody') }}</p>
      <h2 id="terms-liability">{{ t('terms.liabilityTitle') }}</h2>
      <p>{{ t('terms.liabilityBody') }}</p>
      <h2 id="terms-changes">{{ t('terms.changesTitle') }}</h2>
      <p>{{ t('terms.changesBody') }}</p>
      <div class="legal-actions">
        <el-button type="primary" plain @click="$router.push('/matches')">{{ t('common.backToMatches') }}</el-button>
        <el-button plain @click="$router.push('/about')">{{ t('terms.aboutAction') }}</el-button>
        <el-button plain @click="$router.push('/privacy')">{{ t('terms.privacyAction') }}</el-button>
      </div>
    </main>
    <AppFooter />
  </div>
</template>

<script setup>
import AppFooter from '../../components/layout/AppFooter.vue'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import { Football } from '@element-plus/icons-vue'
import { useI18n } from '../../i18n'

const { t } = useI18n()
</script>

<style scoped>
.legal-main { max-width: 820px; margin: 0 auto; padding: 48px 24px; color: var(--ff-text); line-height: 1.85; }
.legal-lead { color: var(--ff-text-muted); margin-bottom: 8px; }
.legal-main h2 { margin: 26px 0 8px; color: var(--ff-primary); font-size: 18px; scroll-margin-top: 84px; }
.legal-main p { color: var(--ff-text-muted); }
.legal-toc { display:flex; flex-wrap:wrap; gap:6px; margin:22px 0 28px; padding:10px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-soft); }
.legal-toc a { padding:6px 9px; border-radius:999px; color:var(--ff-primary); background:var(--ff-primary-soft); font-size:11px; }
.legal-toc a:hover { text-decoration: underline; text-underline-offset: 2px; }
.legal-actions { display:flex; flex-wrap:wrap; gap:8px; margin-top:28px; }
@media (max-width: 680px) { .legal-main { padding:28px 16px 40px; } }
</style>
