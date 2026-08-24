<template>
  <router-view v-if="!legalGateVisible" />
  <AgentLauncher v-if="!legalGateVisible && showAgentLauncher" />
  <AuthDialog v-if="!legalGateVisible" />
  <ConsentBanner v-if="!legalGateVisible" />
  <LegalConsentGate :visible="legalGateVisible" @accepted="legalGateVisible = false" />
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from './stores/user'
import AgentLauncher from './components/agent/AgentLauncher.vue'
import AuthDialog from './components/auth/AuthDialog.vue'
import ConsentBanner from './components/privacy/ConsentBanner.vue'
import LegalConsentGate from './components/privacy/LegalConsentGate.vue'
import { analyticsApi, userApi } from './api'
import { canTrackAnalytics } from './utils/privacyConsent'
import { hasLegalConsent, saveLegalConsent } from './utils/legalConsent'

const route = useRoute()
const userStore = useUserStore()
// Start closed to the application.  For authenticated sessions the server
// status must be checked before rendering any route; anonymous sessions are
// resolved synchronously from the versioned local consent record below.
const legalGateVisible = ref(true)
const showAgentLauncher = computed(() => Boolean(userStore.token) && route.path !== '/agent' && route.path !== '/login' && !route.path.startsWith('/admin'))
const handleAuthRequired = event => {
  if (userStore.token) userStore.logout()
  userStore.openAuthDialog(event?.detail?.redirect || '')
}
onMounted(() => window.addEventListener('football-auth-required', handleAuthRequired))
onBeforeUnmount(() => window.removeEventListener('football-auth-required', handleAuthRequired))
const syncLegalConsent = async token => {
  if (!token) {
    legalGateVisible.value = !hasLegalConsent()
    return
  }
  try {
    const result = await userApi.getLegalConsentStatus()
    const data = result?.data ?? result
    if (data?.accepted) {
      saveLegalConsent()
      legalGateVisible.value = false
    } else {
      legalGateVisible.value = true
    }
  } catch {
    // A logged-in user cannot silently bypass a failed consent status check.
    legalGateVisible.value = true
  }
}
watch(() => userStore.token, syncLegalConsent, { immediate: true })
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
