<template>
  <div class="legal-page ff-page-shell">
    <AppTopNav title="ChenFootball" subtitle="关于我们" :brand-icon="Football" active-path="/about" />
    <main id="app-main" class="legal-main" tabindex="-1">
      <p class="legal-lead">ChenFootball 是面向球迷的足球赛程与预测站点，帮助你快速查看比赛、联赛资料与模型分析，并在关键位置标明数据覆盖与可信度边界。</p>

      <section class="trust-grid" aria-label="信任要点">
        <article class="trust-card">
          <strong>公开赛程</strong>
          <p>比赛列表与联赛积分来自主爬虫同步快照，页面会标注数据状态。</p>
        </article>
        <article class="trust-card">
          <strong>预测边界</strong>
          <p>概率与结论仅供参考；样本不足或未过质量门槛时会明确提示。</p>
        </article>
        <article class="trust-card">
          <strong>隐私可控</strong>
          <p>匿名统计可撤回；账号资料可在个人中心管理，详见隐私说明。</p>
        </article>
      </section>

      <nav class="legal-toc" aria-label="关于目录">
        <a href="#about-product">产品定位</a>
        <a href="#about-data">数据与模型</a>
        <a href="#about-trust">信任与透明</a>
        <a href="#about-disclaimer">免责声明</a>
        <a href="#about-contact">联系与反馈</a>
      </nav>

      <h2 id="about-product">产品定位</h2>
      <p>我们聚焦公开赛程、赛事资料与统一预测快照。重点比赛推荐、收藏提醒和 Agent 问答用于提升浏览效率，而不是替代你自己的判断。</p>

      <h2 id="about-data">数据与模型</h2>
      <p>赛程与积分来自主爬虫同步；预测结果来自赛前特征与模型服务。页面会展示样本覆盖、概率分离度、模型质量门槛等信息；当数据不足或模型未过门槛时，会明确提示“仅供参考”。</p>

      <h2 id="about-trust">信任与透明</h2>
      <ul class="trust-list">
        <li>站点标识：<a href="https://chenfootball.asia" rel="noopener noreferrer">chenfootball.asia</a></li>
        <li>法律文档：<router-link to="/terms">使用条款</router-link>、<router-link to="/privacy">隐私说明</router-link></li>
        <li>预测入口：在比赛页赛程卡点击「查看预测」查看单场分析与质量标注</li>
        <li>不提供博彩建议；禁止将本站数据用于违法用途</li>
      </ul>

      <h2 id="about-disclaimer">免责声明</h2>
      <p>所有预测、概率与 Agent 解读仅供信息参考，不构成投注、投资或其他决策建议。比赛结果受多种不确定因素影响，请以官方赛果为准。</p>

      <h2 id="about-contact">联系与反馈</h2>
      <p>当前未公开独立客服邮箱。你可以通过以下渠道联系或自助处理：</p>
      <ul class="trust-list">
        <li>账号与隐私请求：登录后前往 <router-link to="/profile">个人中心</router-link>，或查阅 <router-link to="/privacy">隐私说明</router-link></li>
        <li>产品体验反馈：使用站内 <router-link to="/agent">Agent</router-link> 描述问题（不发送敏感凭据）</li>
        <li>站点运营标识：ChenFootball · <span class="mono">https://chenfootball.asia</span></li>
      </ul>

      <div class="legal-actions">
        <el-button type="primary" plain @click="$router.push('/matches')">返回比赛</el-button>
        <el-button plain @click="$router.push('/privacy')">隐私说明</el-button>
        <el-button plain @click="$router.push('/terms')">使用条款</el-button>
        <el-button plain @click="$router.push('/agent')">去 Agent 反馈</el-button>
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
.trust-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin: 18px 0 8px;
}
.trust-card {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--ff-border);
  border-radius: 10px;
  background: var(--ff-surface-soft);
}
.trust-card strong { display: block; color: var(--ff-text-strong); font-size: 13px; margin-bottom: 4px; }
.trust-card p { margin: 0; color: var(--ff-text-muted); font-size: 12px; line-height: 1.55; }
.trust-list { margin: 8px 0 0; padding-left: 1.15em; color: var(--ff-text-muted); }
.trust-list li { margin: 6px 0; }
.trust-list a { color: var(--ff-primary); font-weight: 600; text-decoration: underline; text-underline-offset: 2px; }
.trust-list a:focus-visible { outline: 2px solid var(--ff-primary); outline-offset: 2px; border-radius: 4px; }
.mono { font-family: var(--ff-mono); font-size: 12px; color: var(--ff-text); }
.legal-toc { display:flex; flex-wrap:wrap; gap:6px; margin:22px 0 28px; padding:10px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-soft); }
.legal-toc a { padding:8px 10px; min-height: 36px; display:inline-flex; align-items:center; border-radius:999px; color:var(--ff-primary); background:var(--ff-primary-soft); font-size:12px; font-weight: 600; }
.legal-toc a:hover { text-decoration: underline; text-underline-offset: 2px; }
.legal-toc a:focus-visible { outline: 2px solid var(--ff-primary); outline-offset: 2px; }
.legal-actions { display:flex; flex-wrap:wrap; gap:8px; margin-top:28px; }
.legal-actions :deep(.el-button) { min-height: 40px; }
@media (max-width: 680px) {
  .legal-main { padding:28px 16px 40px; }
  .trust-grid { grid-template-columns: 1fr; }
}
</style>
