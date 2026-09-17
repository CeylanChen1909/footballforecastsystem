<template>
  <div class="not-found-page ff-page-shell">
    <AppTopNav title="ChenFootball" subtitle="页面未找到" :brand-icon="Football" active-path="/matches" />
    <main id="app-main" class="not-found-main" tabindex="-1">
      <PageState
        type="empty"
        title="找不到这个页面"
        description="链接可能已失效，或地址输入有误。你可以返回比赛赛程，或查看赛事资料与隐私说明。"
        action-text="返回比赛"
        @action="$router.push('/matches')"
      />
      <div class="not-found-links">
        <el-button plain @click="$router.push('/competitions')">赛事资料</el-button>
        <el-button plain @click="$router.push('/agent')">Agent</el-button>
        <el-button plain @click="$router.push('/privacy')">隐私说明</el-button>
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
