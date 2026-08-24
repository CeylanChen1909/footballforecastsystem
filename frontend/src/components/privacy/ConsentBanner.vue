<template>
  <aside v-if="visible" class="consent-banner" role="region" aria-live="polite" aria-label="隐私提示">
    <div>
      <strong>让产品变得更好</strong>
      <p>我们只记录匿名的页面与功能使用情况，用来改进比赛、预测和 Agent 体验。不会收集密码或聊天正文。</p>
    </div>
    <div class="consent-actions">
      <router-link to="/privacy" class="consent-link" @click="visible = false">隐私说明</router-link>
      <button type="button" class="consent-secondary" @click="choose('essential')">仅必要功能</button>
      <button type="button" class="consent-primary" @click="choose('granted')">允许匿名统计</button>
    </div>
  </aside>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { getAnalyticsConsent, setAnalyticsConsent } from '../../utils/privacyConsent'

const visible = ref(false)
const sync = () => { visible.value = !getAnalyticsConsent() }
const choose = value => { setAnalyticsConsent(value); visible.value = false }
onMounted(() => { sync(); window.addEventListener('football-consent-changed', sync) })
onBeforeUnmount(() => window.removeEventListener('football-consent-changed', sync))
</script>

<style scoped>
.consent-banner { box-sizing:border-box; position:relative; z-index:20; display:flex; align-items:center; gap:16px; width:min(760px, calc(100% - 32px)); max-width:calc(100vw - 32px); margin:14px auto; padding:12px 16px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-lg); background:var(--ff-surface); box-shadow:var(--ff-shadow-lg); }
.consent-banner strong { display:block; color:#183a2b; font-size:13px; }
.consent-banner p { max-width:520px; margin:5px 0 0; color:#6d7e76; font-size:11px; line-height:1.55; }
.consent-actions { display:flex; align-items:center; gap:8px; flex:none; }
.consent-actions button, .consent-link { border:0; border-radius:8px; padding:8px 11px; font-size:11px; cursor:pointer; text-decoration:none; white-space:nowrap; }
.consent-link { color:#527064; }
.consent-secondary { color:#42665a; background:#edf4f0; }
.consent-primary { color:#fff; background:#0f6b4d; }
@media (max-width:680px) {
  .consent-banner { display:block; left:auto; bottom:auto; width:calc(100% - 28px); max-width:calc(100vw - 28px); margin:12px auto; padding:13px 14px; }
  .consent-actions { justify-content:flex-start; flex-wrap:wrap; margin-top:12px; }
  .consent-actions button, .consent-link { padding:8px 9px; }
  .consent-banner { max-height:min(210px, calc(100vh - 24px)); overflow:auto; }
}
</style>
