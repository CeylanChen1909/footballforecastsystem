<template>
  <footer class="app-footer" :aria-label="t('footer.ariaLabel')">
    <div class="app-footer-inner">
      <div class="footer-brand">
        <strong>ChenFootball</strong>
        <span>{{ t('footer.tagline') }}</span>
      </div>
      <nav class="footer-links" :aria-label="t('footer.navLabel')">
        <router-link to="/matches">{{ t('footer.matches') }}</router-link>
        <router-link to="/matches?discover=predict">{{ t('footer.featuredPredictions') }}</router-link>
        <router-link to="/competitions">{{ t('footer.competitions') }}</router-link>
        <router-link to="/agent">{{ t('footer.agent') }}</router-link>
        <router-link to="/about">{{ t('footer.about') }}</router-link>
        <router-link to="/terms">{{ t('footer.terms') }}</router-link>
        <router-link to="/privacy">{{ t('footer.privacy') }}</router-link>
      </nav>
      <p class="footer-note">{{ t('footer.note') }}</p>
      <p class="footer-copy">{{ t('footer.copyright', { year }) }}</p>
    </div>
  </footer>
</template>

<script setup>
import { useI18n } from '../../i18n'

const year = new Date().getFullYear()
const { t } = useI18n()
</script>

<style scoped>
.app-footer {
  margin-top: auto;
  border-top: 1px solid var(--ff-border);
  background: var(--ff-surface-soft);
}
.app-footer-inner {
  max-width: 1100px;
  margin: 0 auto;
  padding: 22px 20px calc(18px + env(safe-area-inset-bottom, 0px));
  display: grid;
  gap: 10px;
}
.footer-brand {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 12px;
}
.footer-brand strong {
  color: var(--ff-text-strong);
  font-size: 14px;
}
.footer-brand span,
.footer-note,
.footer-copy {
  color: var(--ff-text-muted);
  font-size: 12px;
  line-height: 1.6;
}
.footer-links {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
}
.footer-links a {
  color: var(--ff-primary);
  font-size: 13px;
  font-weight: 600;
}
.footer-links a.router-link-active {
  text-decoration: underline;
  text-underline-offset: 3px;
}
.footer-copy {
  color: var(--ff-text-faint);
  font-family: var(--ff-mono);
  font-size: 11px;
}
@media (max-width: 680px) {
  .app-footer-inner {
    padding: 16px 14px calc(88px + env(safe-area-inset-bottom, 0px));
    gap: 8px;
  }
  .footer-brand { flex-direction: column; align-items: flex-start; gap: 4px; }
  .footer-brand span,
  .footer-note { overflow-wrap: anywhere; word-break: break-word; }
  .footer-links { gap: 10px 12px; }
  .footer-links a { min-height: 40px; display: inline-flex; align-items: center; font-size: 12px; }
  .footer-note { margin-top: 2px; }
}
/* r4 a11y footer */
.footer-links a:focus-visible {
  outline: 2px solid var(--ff-primary);
  outline-offset: 3px;
  border-radius: 4px;
}
@media (pointer: coarse) {
  .footer-links a { min-height: 40px; padding: 4px 2px; }
}
</style>
