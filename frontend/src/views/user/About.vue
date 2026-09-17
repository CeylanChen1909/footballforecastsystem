<template>
  <div class="legal-page ff-page-shell">
    <AppTopNav title="ChenFootball" subtitle="关于我们" :brand-icon="Football" active-path="/matches" />
    <main id="app-main" class="legal-main" tabindex="-1">
      <p class="legal-lead">ChenFootball 是面向球迷的足球赛程与预测站点，帮助你快速查看比赛、联赛资料与模型分析，并在关键位置标明数据覆盖与可信度边界。</p>
      <nav class="legal-toc" aria-label="关于目录">
        <a href="#about-product">产品定位</a>
        <a href="#about-data">数据与模型</a>
        <a href="#about-disclaimer">免责声明</a>
        <a href="#about-contact">联系与反馈</a>
      </nav>
      <h2 id="about-product">产品定位</h2>
      <p>我们聚焦公开赛程、赛事资料与统一预测快照。重点比赛推荐、收藏提醒和 Agent 问答用于提升浏览效率，而不是替代你自己的判断。</p>
      <h2 id="about-data">数据与模型</h2>
      <p>赛程与积分来自主爬虫同步；预测结果来自赛前特征与模型服务。页面会展示样本覆盖、概率分离度、模型质量门槛等信息；当数据不足或模型未过门槛时，会明确提示“仅供参考”。</p>
      <h2 id="about-disclaimer">免责声明</h2>
      <p>所有预测、概率与 Agent 解读仅供信息参考，不构成投注、投资或其他决策建议。比赛结果受多种不确定因素影响，请以官方赛果为准。</p>
      <h2 id="about-contact">联系与反馈</h2>
      <p>隐私、账号与数据请求请见隐私说明；功能问题可通过站点反馈或管理员渠道联系。当前未公开独立客服邮箱。</p>
      <div class="legal-actions">
        <el-button type="primary" plain @click="$router.push('/matches')">返回比赛</el-button>
        <el-button plain @click="$router.push('/privacy')">隐私说明</el-button>
        <el-button plain @click="$router.push('/terms')">使用条款</el-button>
      </div>
    </main>
    <AppFooter />
  </div>
</template>

<script setup>
import AppFooter from '../../components/layout/AppFooter.vue'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import { Football } from '@element-plus/icons-vue'
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
