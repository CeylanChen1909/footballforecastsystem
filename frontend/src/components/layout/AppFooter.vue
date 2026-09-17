<template>
  <footer class="app-footer" aria-label="站点页脚">
    <div class="app-footer-inner">
      <div class="footer-brand">
        <strong>ChenFootball</strong>
        <span>足球赛程 · 赛事资料 · 智能预测</span>
      </div>
      <nav class="footer-links" aria-label="页脚导航">
        <router-link to="/matches">比赛</router-link>
        <router-link to="/matches?discover=predict">精选预测</router-link>
        <router-link to="/competitions">赛事资料</router-link>
        <router-link to="/agent">Agent</router-link>
        <router-link to="/about">关于</router-link>
        <router-link to="/terms">使用条款</router-link>
        <router-link to="/privacy">隐私说明</router-link>
      </nav>
      <p class="footer-note">预测仅供参考，不构成投注建议。数据覆盖与模型质量会在页面中明确标注。</p>
      <p class="footer-copy">© {{ year }} ChenFootball</p>
    </div>
  </footer>
</template>

<script setup>
const year = new Date().getFullYear()
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
    padding: 16px 14px calc(72px + env(safe-area-inset-bottom, 0px));
    gap: 8px;
  }
  .footer-brand { flex-direction: column; align-items: flex-start; gap: 4px; }
  .footer-brand span,
  .footer-note { overflow-wrap: anywhere; word-break: break-word; }
  .footer-links { gap: 10px 12px; }
  .footer-links a { min-height: 28px; display: inline-flex; align-items: center; font-size: 12px; }
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
