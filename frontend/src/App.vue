<template>
  <router-view v-if="!legalGateVisible" />
  <AgentLauncher v-if="!legalGateVisible && showAgentLauncher && agentLauncherReady" />
  <AuthDialog v-if="!legalGateVisible" />
  <ConsentBanner v-if="!legalGateVisible" />
  <LegalConsentGate :visible="legalGateVisible" @accepted="handleLegalAccepted" />
</template>

<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from './stores/user'
import LegalConsentGate from './components/privacy/LegalConsentGate.vue'
import { analyticsApi, userApi } from './api'
import { canTrackAnalytics } from './utils/privacyConsent'
import { hasLegalConsent, saveLegalConsent } from './utils/legalConsent'

const AgentLauncher = defineAsyncComponent(() => import('./components/layout/AgentLauncher.vue'))
const agentLauncherReady = ref(false)
const AuthDialog = defineAsyncComponent(() => import('./components/auth/AuthDialog.vue'))
const ConsentBanner = defineAsyncComponent(() => import('./components/privacy/ConsentBanner.vue'))

const route = useRoute()
const userStore = useUserStore()
// Start closed to the application.  For authenticated sessions the server
// status must be checked before rendering any route; anonymous sessions are
// resolved synchronously from the versioned local consent record below.
const legalGateVisible = ref(true)
let legalConsentSyncSequence = 0
const showAgentLauncher = computed(() => Boolean(userStore.token) && route.path !== '/agent' && route.path !== '/login' && !route.path.startsWith('/admin'))
const handleAuthRequired = event => {
  if (userStore.token) userStore.logout()
  userStore.openAuthDialog(event?.detail?.redirect || '')
}
const armAgentLauncher = () => {
  if (agentLauncherReady.value) return
  const enable = () => { agentLauncherReady.value = true }
  if (typeof window !== 'undefined' && 'requestIdleCallback' in window) {
    window.requestIdleCallback(enable, { timeout: 2500 })
  } else {
    setTimeout(enable, 1200)
  }
}
onMounted(() => {
  window.addEventListener('football-auth-required', handleAuthRequired)
  armAgentLauncher()
})
onBeforeUnmount(() => window.removeEventListener('football-auth-required', handleAuthRequired))
const syncLegalConsent = async token => {
  const sequence = ++legalConsentSyncSequence
  if (!token) {
    legalGateVisible.value = !hasLegalConsent()
    return
  }
  try {
    const result = await userApi.getLegalConsentStatus()
    // A user may confirm while this request is in flight. Its old
    // accepted:false response must not reopen the gate after confirmation.
    if (sequence !== legalConsentSyncSequence) return
    const data = result?.data ?? result
    if (data?.accepted) {
      saveLegalConsent()
      legalGateVisible.value = false
    } else {
      legalGateVisible.value = true
    }
  } catch {
    if (sequence !== legalConsentSyncSequence) return
    // A logged-in user cannot silently bypass a failed consent status check.
    legalGateVisible.value = true
  }
}
const handleLegalAccepted = () => {
  // Invalidate any status request started by the login transition before
  // allowing the application route to render.
  legalConsentSyncSequence += 1
  legalGateVisible.value = false
}
watch(() => userStore.token, syncLegalConsent, { immediate: true })
watch(legalGateVisible, (hidden) => { if (!hidden) armAgentLauncher() })
watch(() => route.fullPath, (path) => {
  if (!canTrackAnalytics()) return
  analyticsApi.track('page_view', { page: path }).catch(() => {})
}, { immediate: true })
</script>

<style>


* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html, body, #app {
  min-height: 100%;
}

body {
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', 'Helvetica Neue', Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  background: var(--ff-bg);
  color: var(--ff-text);
}

a {
  text-decoration: none;
  color: inherit;
}

::selection {
  background: rgba(15, 107, 77, 0.18);
}
</style>
