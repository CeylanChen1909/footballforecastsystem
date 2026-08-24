<template>
  <router-view />
  <AgentLauncher v-if="showAgentLauncher" />
  <AuthDialog />
  <ConsentBanner />
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from './stores/user'
import AgentLauncher from './components/agent/AgentLauncher.vue'
import AuthDialog from './components/auth/AuthDialog.vue'
import ConsentBanner from './components/privacy/ConsentBanner.vue'
import { analyticsApi } from './api'
import { canTrackAnalytics } from './utils/privacyConsent'

const route = useRoute()
const userStore = useUserStore()
const showAgentLauncher = computed(() => Boolean(userStore.token) && route.path !== '/agent' && route.path !== '/login' && !route.path.startsWith('/admin'))
const handleAuthRequired = event => {
  if (userStore.token) userStore.logout()
  userStore.openAuthDialog(event?.detail?.redirect || '')
}
onMounted(() => window.addEventListener('football-auth-required', handleAuthRequired))
onBeforeUnmount(() => window.removeEventListener('football-auth-required', handleAuthRequired))
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
