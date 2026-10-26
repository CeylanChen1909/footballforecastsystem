<template>
  <div class="not-found-page ff-page-shell">
    <AppTopNav title="ChenFootball" :subtitle="t('notFound.subtitle')" :brand-icon="Football" active-path="/matches" />
    <main id="app-main" class="not-found-main" tabindex="-1">
      <PageState
        type="empty"
        :title="t('notFound.title')"
        :description="t('notFound.description')"
        :action-text="t('notFound.action')"
        @action="$router.push('/matches')"
      />
      <div class="not-found-links">
        <el-button plain @click="$router.push('/competitions')">{{ t('common.competitions') }}</el-button>
        <el-button plain @click="$router.push('/agent')">{{ t('common.agent') }}</el-button>
        <el-button plain @click="$router.push('/privacy')">{{ t('common.privacy') }}</el-button>
      </div>
    </main>
    <AppFooter />
  </div>
</template>

<script setup>
import AppTopNav from '../../components/layout/AppTopNav.vue'
import AppFooter from '../../components/layout/AppFooter.vue'
import PageState from '../../components/layout/PageState.vue'
import { Football } from '@element-plus/icons-vue'
import { useI18n } from '../../i18n'

const { t } = useI18n()
</script>

<style scoped>
.not-found-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.not-found-main {
  flex: 1;
  width: min(720px, 100%);
  margin: 0 auto;
  padding: 48px 20px 28px;
  display: grid;
  gap: 16px;
  align-content: start;
}
.not-found-links {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}
</style>
